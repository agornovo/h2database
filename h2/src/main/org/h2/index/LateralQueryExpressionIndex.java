/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.index;

import java.util.ArrayList;
import java.util.HashSet;

import org.h2.command.query.AllColumnsForPlan;
import org.h2.command.query.Query;
import org.h2.engine.SessionLocal;
import org.h2.expression.Parameter;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.DerivedTable;
import org.h2.table.QueryExpressionTable;
import org.h2.table.TableFilter;
import org.h2.value.Value;

/**
 * An index for a correlated (lateral) derived table. Unlike
 * {@link RegularQueryExpressionIndex}, this index uses the pre-compiled
 * {@code viewQuery} directly rather than re-parsing the query SQL. This
 * allows the query to reference columns from enclosing queries.
 *
 * <p>This index is created from
 * {@link DerivedTable#createIndex(SessionLocal, int[])} only after
 * {@code mapColumns()} has already propagated all outer-query resolvers into
 * the viewQuery, so {@code viewQuery.prepareExpressions()} and
 * {@code viewQuery.preparePlan()} called in the constructor are guaranteed
 * to succeed.
 */
public final class LateralQueryExpressionIndex extends QueryExpressionIndex {

    /**
     * Cost returned when an outer-query filter dependency has not yet been
     * placed in the join plan. Using this very high value forces the query
     * optimizer to always evaluate this lateral derived table <em>after</em>
     * all of its outer dependencies.
     */
    private static final double DEPENDENCY_VIOLATION_COST = Double.MAX_VALUE / 2;

    /**
     * Table filters from the immediate parent query that this lateral derived
     * table depends on. Used during cost estimation to enforce correct join
     * ordering: the lateral derived table must appear after its dependencies.
     */
    private final HashSet<TableFilter> outerDependencies;

    /**
     * Creates a new lateral query expression index.
     *
     * @param table
     *            the derived table
     * @param viewQuery
     *            the pre-compiled inner query with outer-column references
     *            already mapped
     * @param originalParameters
     *            the original parameters
     * @param session
     *            the session
     */
    public LateralQueryExpressionIndex(QueryExpressionTable table, Query viewQuery,
            ArrayList<Parameter> originalParameters, SessionLocal session) {
        super(table, viewQuery.getPlanSQL(DEFAULT_SQL_FLAGS), originalParameters);
        // Retrieve the outer filter dependencies recorded during mapColumns().
        outerDependencies = (table instanceof DerivedTable)
                ? ((DerivedTable) table).getOuterDependencies()
                : new HashSet<>();
        // Complete preparation: optimize expressions (now that outer columns
        // are mapped) and build the execution plan.
        viewQuery.prepareExpressions();
        viewQuery.preparePlan();
        query = viewQuery;
    }

    @Override
    public boolean isExpired() {
        // Lateral indices are tied to a specific outer query and never expire.
        return false;
    }

    @Override
    public double getCost(SessionLocal session, int[] masks, TableFilter[] filters, int filter,
            SortOrder sortOrder, AllColumnsForPlan allColumnsSet, boolean isSelectCommand) {
        // If there are outer-query filter dependencies and the optimizer is
        // evaluating a join order where not all of them precede this lateral
        // table, return a prohibitively high cost to force the correct order.
        if (filters != null && !outerDependencies.isEmpty()) {
            for (TableFilter dep : outerDependencies) {
                boolean found = false;
                for (int i = 0; i < filter; i++) {
                    if (filters[i] == dep) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return DEPENDENCY_VIOLATION_COST;
                }
            }
        }
        return query.getCost();
    }

    @Override
    public Cursor find(SessionLocal session, SearchRow first, SearchRow last, boolean reverse) {
        assert !reverse;
        ArrayList<Parameter> paramList = query.getParameters();
        if (originalParameters != null) {
            for (Parameter orig : originalParameters) {
                if (orig != null) {
                    int idx = orig.getIndex();
                    Value value = orig.getValue(session);
                    if (idx < paramList.size()) {
                        paramList.get(idx).setValue(value);
                    }
                }
            }
        }
        return new QueryExpressionCursor(this, query.query(0), first, last);
    }

}
