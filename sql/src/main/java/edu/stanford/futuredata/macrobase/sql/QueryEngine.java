package edu.stanford.futuredata.macrobase.sql;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static edu.stanford.futuredata.macrobase.sql.tree.ComparisonExpressionType.EQUAL;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import edu.stanford.futuredata.macrobase.analysis.MBFunction;
import edu.stanford.futuredata.macrobase.analysis.summary.aplinear.APLOutlierSummarizer;
import edu.stanford.futuredata.macrobase.analysis.summary.util.AttributeEncoder;
import edu.stanford.futuredata.macrobase.datamodel.DataFrame;
import edu.stanford.futuredata.macrobase.datamodel.Row;
import edu.stanford.futuredata.macrobase.datamodel.Schema;
import edu.stanford.futuredata.macrobase.datamodel.Schema.ColType;
import edu.stanford.futuredata.macrobase.ingest.CSVDataFrameParser;
import edu.stanford.futuredata.macrobase.sql.tree.AliasedRelation;
import edu.stanford.futuredata.macrobase.sql.tree.AllColumns;
import edu.stanford.futuredata.macrobase.sql.tree.ComparisonExpression;
import edu.stanford.futuredata.macrobase.sql.tree.ComparisonExpressionType;
import edu.stanford.futuredata.macrobase.sql.tree.DereferenceExpression;
import edu.stanford.futuredata.macrobase.sql.tree.DiffQuerySpecification;
import edu.stanford.futuredata.macrobase.sql.tree.DoubleLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.Expression;
import edu.stanford.futuredata.macrobase.sql.tree.FunctionCall;
import edu.stanford.futuredata.macrobase.sql.tree.Identifier;
import edu.stanford.futuredata.macrobase.sql.tree.ImportCsv;
import edu.stanford.futuredata.macrobase.sql.tree.Join;
import edu.stanford.futuredata.macrobase.sql.tree.JoinCriteria;
import edu.stanford.futuredata.macrobase.sql.tree.JoinOn;
import edu.stanford.futuredata.macrobase.sql.tree.JoinUsing;
import edu.stanford.futuredata.macrobase.sql.tree.Literal;
import edu.stanford.futuredata.macrobase.sql.tree.LogicalBinaryExpression;
import edu.stanford.futuredata.macrobase.sql.tree.LogicalBinaryExpression.Type;
import edu.stanford.futuredata.macrobase.sql.tree.NaturalJoin;
import edu.stanford.futuredata.macrobase.sql.tree.NotExpression;
import edu.stanford.futuredata.macrobase.sql.tree.NullLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.OrderBy;
import edu.stanford.futuredata.macrobase.sql.tree.QueryBody;
import edu.stanford.futuredata.macrobase.sql.tree.QuerySpecification;
import edu.stanford.futuredata.macrobase.sql.tree.Relation;
import edu.stanford.futuredata.macrobase.sql.tree.Select;
import edu.stanford.futuredata.macrobase.sql.tree.SelectItem;
import edu.stanford.futuredata.macrobase.sql.tree.SingleColumn;
import edu.stanford.futuredata.macrobase.sql.tree.SortItem;
import edu.stanford.futuredata.macrobase.sql.tree.SortItem.Ordering;
import edu.stanford.futuredata.macrobase.sql.tree.SplitQuery;
import edu.stanford.futuredata.macrobase.sql.tree.StringLiteral;
import edu.stanford.futuredata.macrobase.sql.tree.Table;
import edu.stanford.futuredata.macrobase.sql.tree.TableSubquery;
import edu.stanford.futuredata.macrobase.util.MacrobaseException;
import edu.stanford.futuredata.macrobase.util.MacrobaseSQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class.getSimpleName());
    private static final IntPair DEFAULT_VALUE = new IntPair(0, 0);

    private final Map<String, DataFrame> tablesInMemory;
    private final int numThreads;

    QueryEngine() {
        tablesInMemory = new HashMap<>();
        numThreads = 1; // TODO: add configuration parameter for numThreads
    }

    /**
     * Top-level method for importing tables from CSV files into MacroBase SQL
     *
     * @return A DataFrame that contains the data loaded from the CSV file
     * @throws MacrobaseSQLException if there's an error parsing the CSV file
     */
    DataFrame importTableFromCsv(ImportCsv importStatement) throws MacrobaseSQLException {
        final String filename = importStatement.getFilename();
        final String tableName = importStatement.getTableName().toString();
        final Map<String, ColType> schema = importStatement.getSchema();
        try {
            DataFrame df = new CSVDataFrameParser(filename, schema).load();
            tablesInMemory.put(tableName, df);
            return df;
        } catch (Exception e) {
            throw new MacrobaseSQLException(e.getMessage());
        }
    }

    /**
     * Top-level method for executing a SQL query in MacroBase SQL
     *
     * @return A DataFrame corresponding to the results of the query
     * @throws MacrobaseException If there's an error -- syntactic or logical -- processing the
     * query, an exception is thrown
     */
    DataFrame executeQuery(QueryBody query) throws MacrobaseException {
        if (query instanceof QuerySpecification) {
            QuerySpecification querySpec = (QuerySpecification) query;
            log.debug(querySpec.toString());
            return executeQuerySpec(querySpec);

        } else if (query instanceof DiffQuerySpecification) {
            DiffQuerySpecification diffQuery = (DiffQuerySpecification) query;
            log.debug(diffQuery.toString());
            return executeDiffQuerySpec(diffQuery);
        }
        throw new MacrobaseSQLException(
            "query of type " + query.getClass().getSimpleName() + " not yet supported");
    }

    /**
     * Execute a DIFF query, a query that's specific to MacroBase SQL (i.e., a query that may
     * contain DIFF and SPLIT operators).
     *
     * @return A DataFrame containing the results of the query
     * @throws MacrobaseException If there's an error -- syntactic or logical -- processing the
     * query, an exception is thrown
     */
    private DataFrame executeDiffQuerySpec(final DiffQuerySpecification diffQuery)
        throws MacrobaseException {
        final String outlierColName = "outlier_col";
        final double minRatioMetric = diffQuery.getMinRatioExpression().getMinRatio();
        final double minSupport = diffQuery.getMinSupportExpression().getMinSupport();
        final String ratioMetric = diffQuery.getRatioMetricExpr().getFuncName().toString();
        final int order = diffQuery.getMaxCombo().getValue();
        List<String> explainCols = diffQuery.getAttributeCols().stream()
            .map(Identifier::getValue)
            .collect(toImmutableList());

        DataFrame dfToExplain;

        if (diffQuery.hasTwoArgs()) {
            // case 1: two separate subqueries
            final TableSubquery first = diffQuery.getFirst().get();
            final TableSubquery second = diffQuery.getSecond().get();

            // DIFF-JOIN optimization
            if (matchesDiffJoinCriteria(first.getQuery().getQueryBody(),
                second.getQuery().getQueryBody())) {
                final Join firstJoin = (Join) ((QuerySpecification) first.getQuery().getQueryBody())
                    .getFrom().get();
                final Join secondJoin = (Join) ((QuerySpecification) second.getQuery()
                    .getQueryBody()).getFrom().get();
                return evaluateSQLClauses(diffQuery,
                    executeDiffJoinQuery(firstJoin, secondJoin, explainCols, minRatioMetric,
                        minSupport, ratioMetric, order));
            }

            // execute subqueries
            final DataFrame outliersDf = executeQuery(first.getQuery().getQueryBody());
            final DataFrame inliersDf = executeQuery(second.getQuery().getQueryBody());

            dfToExplain = concatOutliersAndInliers(outlierColName, outliersDf, inliersDf);
        } else {
            // case 2: single SPLIT (...) WHERE ... query
            final SplitQuery splitQuery = diffQuery.getSplitQuery().get();
            final Relation relationToExplain = splitQuery.getInputRelation();
            dfToExplain = getDataFrameForRelation(relationToExplain);

            // add outlier (binary) column by evaluating the WHERE clause
            final BitSet mask = getMask(dfToExplain, splitQuery.getWhereClause());
            final double[] outlierVals = new double[dfToExplain.getNumRows()];
            mask.stream().forEach((i) -> outlierVals[i] = 1.0);
            dfToExplain.addColumn(outlierColName, outlierVals);
        }

        if ((explainCols.size() == 1) && explainCols.get(0).equals("*")) {
            // ON *, explore columns in DataFrame
            explainCols = findExplanationColumns(dfToExplain);
            log.info("Using " + Joiner.on(", ").join(explainCols)
                + " as candidate attributes for explanation");
        }

        // TODO: should be able to check this without having to execute the two subqueries
        if (!dfToExplain.getSchema().hasColumns(explainCols)) {
            throw new MacrobaseSQLException(
                "ON " + Joiner.on(", ").join(explainCols) + " not present in table");
        }

        // TODO: if an explainCol isn't in the SELECT clause, don't include it
        // execute diff
        final APLOutlierSummarizer summarizer = new APLOutlierSummarizer();
        summarizer.setRatioMetric(ratioMetric)
            .setMaxOrder(order)
            .setMinSupport(minSupport)
            .setMinRatioMetric(minRatioMetric)
            .setOutlierColumn(outlierColName)
            .setAttributes(explainCols)
            .setNumThreads(numThreads);

        try {
            summarizer.process(dfToExplain);
        } catch (Exception e) {
            // TODO: get rid of this Exception
            e.printStackTrace();
        }
        final DataFrame resultDf = summarizer.getResults().toDataFrame(explainCols);
        resultDf.renameColumn("outliers", "outlier_count");
        resultDf.renameColumn("count", "total_count");

        return evaluateSQLClauses(diffQuery, resultDf);
    }

    /**
     * Execute DIFF-JOIN query using co-optimized algorithm. NOTE: Must be a Primary Key-Foreign Key
     * Join. TODO: We make the following assumptions in the method below: 1) The two joins are both
     * over the same, single column 2) The join column is of type String 3) The two joins are both
     * inner joins 4) @param explainCols cannot be "*", con only be a single column in T 5) The
     * ratio metric is global_ratio
     *
     * R     S       T ---   ---   ------- a     a     a | CA a     b     b | CA b     c     c | TX
     * b     d     d | TX e     e | FL
     *
     * @return result of the DIFF JOIN
     */
    private DataFrame executeDiffJoinQuery(final Join first, final Join second,
        final List<String> explainColumnNames, final double minRatioMetric, final double minSupport,
        final String ratioMetric, final int order) throws MacrobaseException {

        final DataFrame outlierDf = getDataFrameForRelation(first.getLeft()); // table R
        final DataFrame inlierDf = getDataFrameForRelation(second.getLeft()); // table S
        final DataFrame common = getDataFrameForRelation(first.getRight()); // table T

        final Optional<JoinCriteria> joinCriteriaOpt = first.getCriteria();
        if (!joinCriteriaOpt.isPresent()) {
            throw new MacrobaseSQLException("No clause (e.g., ON, USING) specified in JOIN");
        }

        final List<String> joinColumnName = ImmutableList
            .of(getJoinColumn(joinCriteriaOpt.get(), outlierDf.getSchema(),
                common.getSchema())); // column A1

        final int outlierNumRows = outlierDf.getNumRows();
        final int minSupportThreshold = (int) minSupport * outlierNumRows;
        final double globalRatioDenom =
            outlierNumRows / (outlierNumRows + inlierDf.getNumRows() + 0.0);
        final double minRatioThreshold = minRatioMetric * globalRatioDenom;

        // 1) Execute \delta(\proj_{A1} R, \proj_{A1} S);
        final String[] outlierProjected = outlierDf.project(joinColumnName).getStringColumn(0);
        final String[] inlierProjected = inlierDf.project(joinColumnName).getStringColumn(0);
        // 1a) Encode R, S, and T
        final AttributeEncoder encoder = new AttributeEncoder();
        final List<int[]> encodedValues = encoder.encodeKeyValueAttributes(
            ImmutableList.of(outlierProjected, inlierProjected,
                common.getStringColumnByName(joinColumnName.get(0))),
            common.getStringColsByName(explainColumnNames));

        final Map<Integer, IntPair> foreignKeyCounts = new HashMap<>(); // map foreign key to outlier and inlier counts
        final Set<Integer> candidateForeignKeys = diff(
            encodedValues.get(0), // outlierProjected
            encodedValues.get(1), // inlierProjected
            foreignKeyCounts,
            minRatioThreshold); // returns K, the candidate keys, which may contain some false positives.
        // candidateForeignKeys contains the keys in foreignKeyCounts that exceeded the minRatioThreshold

        // 2) Execute K \semijoin T, to get V, the values in T associated with the candidate keys, and merge
        //    common values that distinct keys may map to
        final Map<Integer, IntPair> valueCounts = new HashMap<>(); // map values to outlier and inlier counts
        semiJoinAndMerge(
            candidateForeignKeys,
            encodedValues.subList(2, encodedValues.size()), // T
            foreignKeyCounts,
            valueCounts,
            minSupportThreshold,
            minRatioThreshold);

        // 3) Construct DataFrame of results
        final Map<String, String[]> stringResultsByCol = new HashMap<>();
        stringResultsByCol.put(explainColumnNames.get(0), new String[valueCounts.size()]);
        final Map<String, double[]> doubleResultsByCol = new HashMap<>();
        doubleResultsByCol.put("global_ratio", new double[valueCounts.size()]);
        doubleResultsByCol.put("support", new double[valueCounts.size()]);
        doubleResultsByCol.put("outlier_count", new double[valueCounts.size()]);
        doubleResultsByCol.put("total_count", new double[valueCounts.size()]);

        int i = 0;
        for (Entry<Integer, IntPair> entry : valueCounts.entrySet()) {
            stringResultsByCol.get(explainColumnNames.get(0))[i] = encoder.decodeValue(entry.getKey());
            final IntPair value = entry.getValue();
            doubleResultsByCol.get("support")[i] = value.a / (outlierNumRows + 0.0);
            doubleResultsByCol.get("global_ratio")[i] =
                value.a / (value.a + value.b + 0.0) / globalRatioDenom;
            doubleResultsByCol.get("outlier_count")[i] = value.a;
            doubleResultsByCol.get("total_count")[i] = value.a + value.b;
            ++i;
        }
        final DataFrame result = new DataFrame();
        result.addColumn(explainColumnNames.get(0),
            stringResultsByCol.get(explainColumnNames.get(0)));
        result.addColumn("support", doubleResultsByCol.get("support"));
        result.addColumn("global_ratio", doubleResultsByCol.get("global_ratio"));
        result.addColumn("outlier_count", doubleResultsByCol.get("outlier_count"));
        result.addColumn("total_count", doubleResultsByCol.get("total_count"));
        return result;
    }

    private void semiJoinAndMerge(Set<Integer> candidateForeignKeys, List<int[]> encodedValues,
        Map<Integer, IntPair> foreignKeyCounts, Map<Integer, IntPair> valueCounts,
        final int minSupportThreshold, final double minRatioThreshold) {
        final int[] primaryKeyCol = encodedValues.get(0);
        // TODO: right now, we only handle one explain column
        final int[] valueCol = encodedValues.get(1);
        // 1) R \semijoin T: Go through the primary key column and see what candidateForeignKeys are contained
        for (int i = 0; i < primaryKeyCol.length; ++i) {
            final int primaryKey = primaryKeyCol[i];
            if (candidateForeignKeys.contains(primaryKey)) {
                final IntPair foreignKeyCount = foreignKeyCounts
                    .get(primaryKey); // this always exists, never need to check for null
                // extract the corresponding value for the candidate key
                final int val = valueCol[i];
                final IntPair valueCount = valueCounts.get(val);
                if (valueCount == null) {
                    valueCounts.put(val, new IntPair(foreignKeyCount.a, foreignKeyCount.b));
                } else {
                    // if the value already exists, merge the foreign key counts
                    valueCount.a += foreignKeyCount.a; // outlier count
                    valueCount.b += foreignKeyCount.b; // inlier count
                }
            }
        }
        // 2) Go through primary key column again, check if anything maps to the same values we found in the
        for (int i = 0; i < valueCol.length; ++i) {
            final int val = valueCol[i];
            final IntPair valueCount = valueCounts.get(val);
            if (valueCount == null) {
                continue;
            }
            // extract the corresponding foreign key, merge the foreign key counts
            final int primaryKey = primaryKeyCol[i];
            if (candidateForeignKeys.contains(primaryKey)) {
                continue;
            }
            final IntPair foreignKeyCount = foreignKeyCounts
                .getOrDefault(primaryKey, DEFAULT_VALUE);
            valueCount.a += foreignKeyCount.a; // outlier count
            valueCount.b += foreignKeyCount.b; // inlier count
        }
        // 3) Prune anything that doesn't have enough support or no longer exceeds the minRatioThreshold
        valueCounts.entrySet().removeIf((entry) -> {
            final IntPair value = entry.getValue();
            return value.a < minSupportThreshold
                || (value.a / (value.a + value.b + 0.0)) < minRatioThreshold;
        });
    }

    private Set<Integer> diff(final int[] outliers, final int[] inliers,
        final Map<Integer, IntPair> foreignKeyCounts, double minRatioThreshold) {
        for (int outlier : outliers) {
            final IntPair value = foreignKeyCounts.get(outlier);
            if (value != null) {
                value.a++;
            } else {
                foreignKeyCounts.put(outlier, new IntPair(1, 0));
            }
        }
        for (int inlier : inliers) {
            final IntPair value = foreignKeyCounts.get(inlier);
            if (value != null) {
                value.b++;
            } else {
                foreignKeyCounts.put(inlier, new IntPair(0, 1));
            }
        }
        final ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
        for (Entry<Integer, IntPair> entry : foreignKeyCounts.entrySet()) {
            final IntPair value = entry.getValue();
            if ((value.a / (value.a + value.b + 0.0)) > minRatioThreshold) {
                builder.add(entry.getKey());
            }
        }
        return builder.build();
    }

    /**
     * @return true both relations are NATURAL JOIN subqueries that share a common table, e.g., R
     * \join T, and S \join T TODO: enforce FK-PK Join
     */
    private boolean matchesDiffJoinCriteria(final QueryBody first, final QueryBody second) {
        if (!(first instanceof QuerySpecification) || !(second instanceof QuerySpecification)) {
            return false;
        }
        final QuerySpecification firstQuerySpec = (QuerySpecification) first;
        final QuerySpecification secondQuerySpec = (QuerySpecification) second;
        final Relation firstRelation = firstQuerySpec.getFrom().get();
        final Relation secondRelation = secondQuerySpec.getFrom().get();

        if (!(firstRelation instanceof Join) || !(secondRelation instanceof Join)) {
            return false;
        }

        final Join firstJoin = (Join) firstRelation;
        final Join secondJoin = (Join) secondRelation;

        if (!(firstJoin.getCriteria().get() instanceof NaturalJoin) || !(secondJoin.getCriteria()
            .get() instanceof NaturalJoin)) {
            return false;
        }
        // TODO: my not necessarily be R \join T and S \join T; could be T \join R and T \join S. Need to support both
        return (firstJoin.getRight().equals(secondJoin.getRight()) &&
            !firstJoin.getLeft().equals(secondJoin.getLeft()));
    }

    /**
     * Find columns that should be included in the "ON col1, col2, ..., coln" clause
     *
     * @return List of columns (as Strings)
     */
    private List<String> findExplanationColumns(DataFrame dfToExplain) {
        Builder<String> builder = ImmutableList.builder();
        final int numRowsToSample =
            dfToExplain.getNumRows() < 1000 ? dfToExplain.getNumRows() : 1000;
        final List<String> stringCols = dfToExplain.getSchema()
            .getColumnNamesByType(ColType.STRING);
        for (String colName : stringCols) {
            final String[] colValues = dfToExplain.getStringColumnByName(colName);
            final Set<String> set = new HashSet<>();
            set.addAll(Arrays.asList(colValues).subList(0, numRowsToSample));
            if (set.size() < numRowsToSample / 4) {
                // if number of distinct elements is less than 1/4 the number of sampled rows,
                // include it
                builder.add(colName);
            }
        }
        return builder.build();
    }

    /**
     * Returns all values in the SELECT clause of a given query that are {@link FunctionCall}
     * objects, which are UDFs (e.g., "percentile(column_name)").
     *
     * @param select The Select clause
     * @return The items in the Select clause that correspond to UDFs returned as a List of {@link
     * SingleColumn}
     */
    private List<SingleColumn> getUDFsInSelect(final Select select) {
        final List<SingleColumn> udfs = new ArrayList<>();
        for (SelectItem item : select.getSelectItems()) {
            if (item instanceof SingleColumn) {
                final SingleColumn col = (SingleColumn) item;
                if (col.getExpression() instanceof FunctionCall) {
                    udfs.add(col);
                }
            }
        }
        return udfs;
    }

    /**
     * Concatenate two DataFrames -- outlier and inlier -- into a single DataFrame, with a new
     * column that stores 1 if the row is originally from the outlier DF and 0 if it's from the
     * inlier DF
     *
     * @param outlierColName The name of the binary column that denotes outlier/inlier
     * @param outliersDf outlier DataFrame
     * @param inliersDf inlier DataFrame
     * @return new DataFrame that contains rows from both DataFrames, along with the extra binary
     * column
     */
    private DataFrame concatOutliersAndInliers(final String outlierColName,
        final DataFrame outliersDf, final DataFrame inliersDf) {

        // Add column "outlier_col" to both outliers (all 1.0) and inliers (all 0.0)
        outliersDf.addColumn(outlierColName,
            DoubleStream.generate(() -> 1.0).limit(outliersDf.getNumRows()).toArray());
        inliersDf.addColumn(outlierColName,
            DoubleStream.generate(() -> 0.0).limit(inliersDf.getNumRows()).toArray());
        return DataFrame.unionAll(Lists.newArrayList(outliersDf, inliersDf));
    }

    /**
     * Evaluate standard SQL clauses: SELECT, WHERE, ORDER BY, and LIMIT. TODO: support GROUP BY and
     * HAVING clauses
     *
     * @param query the query that contains the clauses
     * @param df the DataFrame to apply these clauses to
     * @return a new DataFrame, the result of applying all of these clauses
     */
    private DataFrame evaluateSQLClauses(final QueryBody query, final DataFrame df)
        throws MacrobaseException {
        DataFrame resultDf = evaluateUDFs(df, getUDFsInSelect(query.getSelect()));
        resultDf = evaluateWhereClause(resultDf, query.getWhere());
        resultDf = evaluateSelectClause(resultDf, query.getSelect());
        // TODO: what if you order by something that's not in the SELECT clause?
        resultDf = evaluateOrderByClause(resultDf, query.getOrderBy());
        return evaluateLimitClause(resultDf, query.getLimit());
    }

    /**
     * Evaluate ORDER BY clause. For now, we only support sorting by a single column.
     */
    private DataFrame evaluateOrderByClause(DataFrame df, Optional<OrderBy> orderByOpt)
        throws MacrobaseSQLException {
        if (!orderByOpt.isPresent()) {
            return df;
        }
        final OrderBy orderBy = orderByOpt.get();
        // For now, we only support sorting by a single column
        // TODO: support multi-column sort
        final SortItem sortItem = orderBy.getSortItems().get(0);
        final Expression sortKey = sortItem.getSortKey();
        final String sortCol;
        if (sortKey instanceof Identifier) {
            sortCol = ((Identifier) sortKey).getValue();
        } else if (sortKey instanceof DereferenceExpression) {
            sortCol = sortKey.toString();
        } else {
            throw new MacrobaseSQLException("Unsupported expression type in ORDER BY");
        }
        return df.orderBy(sortCol, sortItem.getOrdering() == Ordering.ASCENDING);
    }

    /**
     * Execute a standard SQL query (i.e., a query that only contains ANSI SQL terms, and does not
     * contain any DIFF or SPLIT operators). For now, we ignore GROUP BY, HAVING, and JOIN clauses
     *
     * @return A DataFrame containing the results of the SQL query
     */
    private DataFrame executeQuerySpec(final QuerySpecification query)
        throws MacrobaseException {
        final Relation from = query.getFrom().get();
        final DataFrame df;
        if (from instanceof Join) {
            final Join join = (Join) from;
            df = evaluateJoin(join);
        } else if (from instanceof Table) {
            final Table table = (Table) from;
            df = getTable(table.getName().toString());
        } else {
            throw new MacrobaseSQLException("Unsupported argument in FROM clause");
        }
        return evaluateSQLClauses(query, df);
    }

    /**
     * TODO
     */
    private DataFrame evaluateJoin(Join join) throws MacrobaseException {
        final DataFrame left = getDataFrameForRelation(join.getLeft());
        final DataFrame right = getDataFrameForRelation(join.getRight());

        final boolean leftSmaller = left.getNumRows() < right.getNumRows();
        final DataFrame smaller = leftSmaller ? left : right;
        final DataFrame bigger = leftSmaller ? right : left;

        final String smallerName = leftSmaller ? getName(join.getLeft()) : getName(join.getRight());
        final String biggerName = leftSmaller ? getName(join.getRight()) : getName(join.getLeft());

        final Optional<JoinCriteria> joinCriteriaOpt = join.getCriteria();
        if (!joinCriteriaOpt.isPresent()) {
            throw new MacrobaseSQLException("No clause (e.g., ON, USING) specified in JOIN");
        }

        final Schema biggerSchema = bigger.getSchema();
        final Schema smallerSchema = smaller.getSchema();
        final String joinColumn = getJoinColumn(joinCriteriaOpt.get(), biggerSchema, smallerSchema);
        switch (join.getType()) {
            case INNER:
                final int biggerColIndex, smallerColIndex;
                try {
                    biggerColIndex = biggerSchema.getColumnIndex(joinColumn);
                    smallerColIndex = smallerSchema.getColumnIndex(joinColumn);
                } catch (UnsupportedOperationException e) {
                    throw new MacrobaseSQLException(e.getMessage());
                }
                final ColType biggerColType = bigger.getSchema().getColumnType(biggerColIndex);
                final ColType smallerColType = smaller.getSchema().getColumnType(smallerColIndex);
                if (biggerColType != smallerColType) {
                    throw new MacrobaseSQLException(
                        "Column " + joinColumn + " has type " + joinColumn + " in one table but "
                            + " type " + joinColumn + " in the other");
                }

                // String column values that will be added to DataFrame
                final Map<String, List<String>> biggerStringResults = new HashMap<>();
                final Map<String, List<String>> smallerStringResults = new HashMap<>();
                for (String colName : biggerSchema.getColumnNamesByType(ColType.STRING)) {
                    biggerStringResults.put(colName, new LinkedList<>());
                }
                for (String colName : smallerSchema.getColumnNamesByType(ColType.STRING)) {
                    if (colName.equals(joinColumn)) {
                        continue;
                    }
                    smallerStringResults.put(colName, new LinkedList<>());
                }

                // double column values that will be added to the DataFrame
                final Map<String, List<Double>> biggerDoubleResults = new HashMap<>();
                final Map<String, List<Double>> smallerDoubleResults = new HashMap<>();
                for (String colName : biggerSchema.getColumnNamesByType(ColType.DOUBLE)) {
                    biggerDoubleResults.put(colName, new LinkedList<>());
                }
                for (String colName : smallerSchema.getColumnNamesByType(ColType.DOUBLE)) {
                    if (colName.equals(joinColumn)) {
                        continue;
                    }
                    smallerDoubleResults.put(colName, new LinkedList<>());
                }

                BiPredicate<Row, Row> lambda = getJoinLambda(biggerColIndex, smallerColIndex,
                    biggerColType);
                for (Row big : bigger.getRowIterator()) {
                    for (Row small : smaller.getRowIterator()) {
                        if (lambda.test(big, small)) {
                            // Add from big
                            for (String colName : biggerStringResults.keySet()) {
                                biggerStringResults.get(colName).add(big.getAs(colName));
                            }
                            for (String colName : biggerDoubleResults.keySet()) {
                                biggerDoubleResults.get(colName).add(big.getAs(colName));
                            }
                            // Add from small
                            for (String colName : smallerStringResults.keySet()) {
                                smallerStringResults.get(colName).add(small.getAs(colName));
                            }
                            for (String colName : smallerDoubleResults.keySet()) {
                                smallerDoubleResults.get(colName).add(small.getAs(colName));
                            }
                        }
                    }
                }

                final DataFrame df = new DataFrame();
                // Add String results
                for (String colName : biggerStringResults.keySet()) {
                    final String colNameForOutput =
                        smallerSchema.hasColumn(colName) && !colName.equals(joinColumn) ? biggerName
                            + "." + colName : colName;
                    df.addColumn(colNameForOutput,
                        biggerStringResults.get(colName).toArray(new String[0]));
                }
                for (String colName : smallerStringResults.keySet()) {
                    final String colNameForOutput =
                        biggerSchema.hasColumn(colName) && !colName.equals(joinColumn) ? smallerName
                            + "." + colName : colName;
                    df.addColumn(colNameForOutput,
                        smallerStringResults.get(colName).toArray(new String[0]));
                }
                // Add double results
                for (String colName : biggerDoubleResults.keySet()) {
                    final String colNameForOutput =
                        smallerSchema.hasColumn(colName) && !colName.equals(joinColumn) ? biggerName
                            + "." + colName : colName;
                    df.addColumn(colNameForOutput,
                        biggerDoubleResults.get(colName).stream().mapToDouble((x) -> x).toArray());
                }
                for (String colName : smallerDoubleResults.keySet()) {
                    final String colNameForOutput =
                        biggerSchema.hasColumn(colName) && !colName.equals(joinColumn) ? smallerName
                            + "." + colName : colName;
                    df.addColumn(colNameForOutput,
                        smallerDoubleResults.get(colName).stream().mapToDouble((x) -> x).toArray());
                }
                return df;
            default:
                throw new MacrobaseSQLException("Join type " + join.getType() + "not supported");
        }
    }

    /**
     * TODO
     */
    private String getName(Relation relation) throws MacrobaseSQLException {
        if (relation instanceof Table) {
            return ((Table) relation).getName().toString();
        } else if (relation instanceof AliasedRelation) {
            return ((AliasedRelation) relation).getAlias().getValue();
        } else {
            throw new MacrobaseSQLException("Not a supported relation for getName");
        }
    }

    // ********************* Helper methods for evaluating Join expressions **********************

    /**
     * TODO
     */
    private String getJoinColumn(final JoinCriteria joinCriteria,
        Schema biggerSchema, Schema smallerSchema) throws MacrobaseSQLException {
        if (joinCriteria instanceof JoinOn) {
            final JoinOn joinOn = (JoinOn) joinCriteria;
            final Expression joinExpression = joinOn.getExpression();
            if (!(joinExpression instanceof Identifier)) {
                throw new MacrobaseSQLException("Only one column allowed with JOIN ON");
            }
            return ((Identifier) joinExpression).getValue();
        } else if (joinCriteria instanceof JoinUsing) {
            final JoinUsing joinUsing = (JoinUsing) joinCriteria;
            if (joinUsing.getColumns().size() != 1) {
                throw new MacrobaseSQLException("Only one column allowed with JOIN USING");
            }
            return joinUsing.getColumns().get(0).getValue();
        } else if (joinCriteria instanceof NaturalJoin) {
            final List<String> intersection = biggerSchema.getColumnNames().stream()
                .filter(smallerSchema.getColumnNames()::contains).collect(toImmutableList());
            if (intersection.size() != 1) {
                throw new MacrobaseSQLException("Exactly one column allowed with NATURAL JOIN");
            }
            return intersection.get(0);
        } else {
            throw new MacrobaseSQLException(
                "Unsupported join criteria: " + joinCriteria.toString());
        }
    }

    /**
     * TODO
     */
    private BiPredicate<Row, Row> getJoinLambda(final int biggerColIndex, final int smallerColIndex,
        ColType colType) throws MacrobaseSQLException {
        if (colType == ColType.DOUBLE) {
            final BiDoublePredicate lambda = generateBiDoubleLambda(EQUAL);
            return (big, small) -> lambda.test((double) big.getVals().get(biggerColIndex),
                (double) small.getVals().get(smallerColIndex));
        } else {
            // ColType.STRING
            final BiPredicate<String, String> lambda = generateBiStringLambda(EQUAL);
            return (big, small) -> lambda.test((String) big.getVals().get(biggerColIndex),
                (String) small.getVals().get(smallerColIndex));
        }
    }

    /**
     * TODO
     */
    private BiDoublePredicate generateBiDoubleLambda(ComparisonExpressionType compareExprType)
        throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return (x, y) -> x == y;
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't.
                // x and y can never be NULL here, so it's the same as NOT_EQUAL
                return (x, y) -> x != y;
            case LESS_THAN:
                return (x, y) -> x < y;
            case LESS_THAN_OR_EQUAL:
                return (x, y) -> x <= y;
            case GREATER_THAN:
                return (x, y) -> x > y;
            case GREATER_THAN_OR_EQUAL:
                return (x, y) -> x >= y;
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }

    /**
     * TODO
     */
    private BiPredicate<String, String> generateBiStringLambda(
        ComparisonExpressionType compareExprType) throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return Objects::equals;
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't
                return (x, y) -> !Objects.equals(x, y);
            case LESS_THAN:
                return (x, y) -> x.compareTo(y) < 0;
            case LESS_THAN_OR_EQUAL:
                return (x, y) -> x.compareTo(y) <= 0;
            case GREATER_THAN:
                return (x, y) -> x.compareTo(y) > 0;
            case GREATER_THAN_OR_EQUAL:
                return (x, y) -> x.compareTo(y) >= 0;
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }

    /**
     * TODO
     */
    private DataFrame getDataFrameForRelation(final Relation relation) throws MacrobaseException {
        if (relation instanceof TableSubquery) {
            final QueryBody subquery = ((TableSubquery) relation).getQuery().getQueryBody();
            return executeQuery(subquery);
        } else if (relation instanceof AliasedRelation) {
            return getTable(
                ((Table) ((AliasedRelation) relation).getRelation()).getName().toString());
        } else if (relation instanceof Table) {
            return getTable(((Table) relation).getName().toString());
        } else {
            throw new MacrobaseSQLException("Unsupported relation type");
        }
    }

    /**
     * Get table as DataFrame that has previously been loaded into memory
     *
     * @param tableName String that uniquely identifies table
     * @return a shallow copy of the DataFrame for table; the original DataFrame is never returned,
     * so that we keep it immutable
     * @throws MacrobaseSQLException if the table has not been loaded into memory and does not
     * exist
     */
    private DataFrame getTable(String tableName) throws MacrobaseSQLException {
        if (!tablesInMemory.containsKey(tableName)) {
            throw new MacrobaseSQLException("Table " + tableName + " does not exist");
        }
        return tablesInMemory.get(tableName).copy();
    }

    /**
     * Evaluate only the UDFs of SQL query and return a new DataFrame with the UDF-generated columns
     * added to the input DataFrame. If there are no UDFs (i.e. @param udfCols is empty), the input
     * DataFrame is returned as is.
     *
     * @param inputDf The DataFrame to evaluate the UDFs on
     * @param udfCols The List of UDFs to evaluate
     */
    private DataFrame evaluateUDFs(final DataFrame inputDf, final List<SingleColumn> udfCols)
        throws MacrobaseException {

        // create shallow copy, so modifications don't persist on the original DataFrame
        final DataFrame resultDf = inputDf.copy();
        for (SingleColumn udfCol : udfCols) {
            final FunctionCall func = (FunctionCall) udfCol.getExpression();
            // for now, if UDF is a.b.c.d(), ignore "a.b.c."
            final String funcName = func.getName().getSuffix();
            // for now, assume func.getArguments returns at least 1 argument, always grab the first
            final MBFunction mbFunction = MBFunction.getFunction(funcName,
                func.getArguments().stream().map(Expression::toString).findFirst().get());

            // modify resultDf in place, add column; mbFunction is evaluated on input DataFrame
            resultDf.addColumn(udfCol.toString(), mbFunction.apply(inputDf));
        }
        return resultDf;
    }

    /**
     * Evaluate Select clause of SQL query, but only once all UDFs from the clause have been
     * removed. If the clause is 'SELECT *' the same DataFrame is returned unchanged. TODO: add
     * support for DISTINCT queries
     *
     * @param df The DataFrame to apply the Select clause on
     * @param select The Select clause
     * @return A new DataFrame with the result of the Select clause applied
     */
    private DataFrame evaluateSelectClause(DataFrame df, final Select select) {
        final List<SelectItem> items = select.getSelectItems();
        for (SelectItem item : items) {
            // If we find '*' -> relation is unchanged
            if (item instanceof AllColumns) {
                return df;
            }
        }
        final List<String> projections = items.stream().map(SelectItem::toString)
            .collect(toImmutableList());
        return df.project(projections);
    }

    /**
     * Evaluate LIMIT clause of SQL query, return the top n rows of the DataFrame, where `n' is
     * specified in "LIMIT n"
     *
     * @param df The DataFrame to apply the LIMIT clause on
     * @param limitStr The number of rows (either an integer or "ALL") as a String in the LIMIT
     * clause
     * @return A new DataFrame with the result of the LIMIT clause applied
     */

    private DataFrame evaluateLimitClause(final DataFrame df, final Optional<String> limitStr) {
        if (limitStr.isPresent()) {
            try {
                return df.limit(Integer.parseInt(limitStr.get()));
            } catch (NumberFormatException e) {
                // LIMIT ALL, catch NumberFormatException and do nothing
                return df;
            }
        }
        return df;
    }

    /**
     * Evaluate Where clause of SQL query
     *
     * @param df the DataFrame to filter
     * @param whereClauseOpt An Optional Where clause (of type Expression) to evaluate for each row
     * in <tt>df</tt>
     * @return A new DataFrame that contains the rows for which @whereClause evaluates to true. If
     * <tt>whereClauseOpt</tt> is not Present, we return <tt>df</tt>
     */
    private DataFrame evaluateWhereClause(final DataFrame df,
        final Optional<Expression> whereClauseOpt) throws MacrobaseException {
        if (!whereClauseOpt.isPresent()) {
            return df;
        }
        final Expression whereClause = whereClauseOpt.get();
        final BitSet mask = getMask(df, whereClause);
        return df.filter(mask);
    }

    // ********************* Helper methods for evaluating Where clauses **********************

    /**
     * Recursive method that, given a Where clause, generates a boolean mask (a BitSet) applying the
     * clause to a DataFrame
     *
     * @throws MacrobaseSQLException Only comparison expressions (e.g., WHERE x = 42) and logical
     * AND/OR/NOT combinations of such expressions are supported; exception is thrown otherwise.
     */
    private BitSet getMask(DataFrame df, Expression whereClause) throws MacrobaseException {
        if (whereClause instanceof NotExpression) {
            final NotExpression notExpr = (NotExpression) whereClause;
            final BitSet mask = getMask(df, notExpr.getValue());
            mask.flip(0, df.getNumRows());
            return mask;

        } else if (whereClause instanceof LogicalBinaryExpression) {
            final LogicalBinaryExpression binaryExpr = (LogicalBinaryExpression) whereClause;
            final BitSet leftMask = getMask(df, binaryExpr.getLeft());
            final BitSet rightMask = getMask(df, binaryExpr.getRight());
            if (binaryExpr.getType() == Type.AND) {
                leftMask.and(rightMask);
                return leftMask;
            } else {
                // Type.OR
                leftMask.or(rightMask);
                return leftMask;
            }

        } else if (whereClause instanceof ComparisonExpression) {
            // base case
            final ComparisonExpression compareExpr = (ComparisonExpression) whereClause;
            final Expression left = compareExpr.getLeft();
            final Expression right = compareExpr.getRight();
            final ComparisonExpressionType type = compareExpr.getType();

            if (left instanceof Literal && right instanceof Literal) {
                final boolean val = left.equals(right);
                final BitSet mask = new BitSet(df.getNumRows());
                mask.set(0, df.getNumRows(), val);
                return mask;
            } else if (left instanceof Literal && right instanceof Identifier) {
                return maskForPredicate(df, (Literal) left, (Identifier) right, type);
            } else if (right instanceof Literal && left instanceof Identifier) {
                return maskForPredicate(df, (Literal) right, (Identifier) left, type);
            } else if (left instanceof FunctionCall && right instanceof Literal) {
                return maskForPredicate(df, (FunctionCall) left, (Literal) right, type);
            } else if (right instanceof FunctionCall && left instanceof Literal) {
                return maskForPredicate(df, (FunctionCall) right, (Literal) left, type);
            }
        }
        throw new MacrobaseSQLException("Boolean expression not supported");
    }

    private BitSet maskForPredicate(DataFrame df, FunctionCall func, Literal val,
        final ComparisonExpressionType type)
        throws MacrobaseException {
        final String funcName = func.getName().getSuffix();
        final MBFunction mbFunction = MBFunction.getFunction(funcName,
            func.getArguments().stream().map(Expression::toString).findFirst().get());
        final double[] col = mbFunction.apply(df);
        final DoublePredicate predicate = getPredicate(((DoubleLiteral) val).getValue(), type);
        final BitSet mask = new BitSet(col.length);
        for (int i = 0; i < col.length; ++i) {
            if (predicate.test(col[i])) {
                mask.set(i);
            }
        }
        return mask;
    }


    /**
     * The base case for {@link QueryEngine#getMask(DataFrame, Expression)}; returns a boolean mask
     * (as a BitSet) for a single comparision expression (e.g., WHERE x = 42)
     *
     * @param df The DataFrame on which to evaluate the comparison expression
     * @param literal The constant argument in the expression (e.g., 42)
     * @param identifier The column variable argument in the expression (e.g., x)
     * @param compExprType One of =, !=, >, >=, <, <=, or IS DISTINCT FROM
     * @throws MacrobaseSQLException if the literal's type doesn't match the type of the column
     * variable, an exception is thrown
     */
    private BitSet maskForPredicate(final DataFrame df, final Literal literal,
        final Identifier identifier, final ComparisonExpressionType compExprType)
        throws MacrobaseSQLException {
        final String colName = identifier.getValue();
        final int colIndex;
        try {
            colIndex = df.getSchema().getColumnIndex(colName);
        } catch (UnsupportedOperationException e) {
            throw new MacrobaseSQLException(e.getMessage());
        }
        final ColType colType = df.getSchema().getColumnType(colIndex);

        if (colType == ColType.DOUBLE) {
            if (!(literal instanceof DoubleLiteral)) {
                throw new MacrobaseSQLException(
                    "Column " + colName + " has type " + colType + ", but " + literal
                        + " is not a DoubleLiteral");
            }

            return df.getMaskForFilter(colIndex,
                getPredicate(((DoubleLiteral) literal).getValue(), compExprType));
        } else {
            // colType == ColType.STRING
            if (literal instanceof StringLiteral) {
                return df.getMaskForFilter(colIndex,
                    getPredicate(((StringLiteral) literal).getValue(), compExprType));
            } else if (literal instanceof NullLiteral) {
                return df.getMaskForFilter(colIndex, getPredicate(null, compExprType));
            } else {
                throw new MacrobaseSQLException(
                    "Column " + colName + " has type " + colType + ", but " + literal
                        + " is not StringLiteral");
            }
        }
    }

    /**
     * Return a Java Predicate expression for a given comparison type and constant value of type
     * double. (See {@link QueryEngine#getPredicate(String, ComparisonExpressionType)} for handling
     * a String argument.)
     *
     * @param y The constant value
     * @param compareExprType One of =, !=, >, >=, <, <=, or IS DISTINCT FROM
     * @return A {@link DoublePredicate}, that wraps the constant y in a closure
     * @throws MacrobaseSQLException If a comparsion type is passed in that is not supported, an
     * exception is thrown
     */
    private DoublePredicate getPredicate(double y, ComparisonExpressionType compareExprType)
        throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return (x) -> x == y;
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't.
                // x and y can never be NULL here, so it's the same as NOT_EQUAL
                return (x) -> x != y;
            case LESS_THAN:
                return (x) -> x < y;
            case LESS_THAN_OR_EQUAL:
                return (x) -> x <= y;
            case GREATER_THAN:
                return (x) -> x > y;
            case GREATER_THAN_OR_EQUAL:
                return (x) -> x >= y;
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }

    /**
     * Return a Java Predicate expression for a given comparison type and constant value of type
     * String. (See {@link QueryEngine#getPredicate(double, ComparisonExpressionType)} for handling
     * a double argument.)
     *
     * @param y The constant value
     * @param compareExprType One of =, !=, >, >=, <, <=, or IS DISTINCT FROM
     * @return A {@link Predicate<Object>}, that wraps the constant y in a closure. A
     * Predicate<String> is not returned for compatibility with {@link DataFrame#filter(int,
     * Predicate)}.
     * @throws MacrobaseSQLException If a comparsion type is passed in that is not supported, an
     * exception is thrown
     */
    private Predicate<String> getPredicate(final String y,
        final ComparisonExpressionType compareExprType) throws MacrobaseSQLException {
        switch (compareExprType) {
            case EQUAL:
                return (x) -> Objects.equals(x, y);
            case NOT_EQUAL:
            case IS_DISTINCT_FROM:
                // IS DISTINCT FROM is true when x and y have different values or
                // if one of them is NULL and the other isn't
                return (x) -> !Objects.equals(x, y);
            case LESS_THAN:
                return (x) -> x.compareTo(y) < 0;
            case LESS_THAN_OR_EQUAL:
                return (x) -> x.compareTo(y) <= 0;
            case GREATER_THAN:
                return (x) -> x.compareTo(y) > 0;
            case GREATER_THAN_OR_EQUAL:
                return (x) -> x.compareTo(y) >= 0;
            default:
                throw new MacrobaseSQLException(compareExprType + " is not supported");
        }
    }
}

