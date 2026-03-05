/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.util.ArrayList;
import java.util.HashSet;

import org.h2.api.ErrorCode;
import org.h2.command.QueryScope;
import org.h2.command.query.Query;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.index.LateralQueryExpressionIndex;
import org.h2.index.QueryExpressionIndex;
import org.h2.index.RegularQueryExpressionIndex;
import org.h2.message.DbException;
import org.h2.util.StringUtils;
import org.h2.value.Value;

/**
 * A derived table.
 */
public final class DerivedTable extends QueryExpressionTable {

    private final String querySQL;

    private final Query topQuery;

    private final ArrayList<Parameter> originalParameters;

    /**
     * Whether this derived table has correlated (outer query) column
     * references. Such references cannot be resolved during construction
     * and require a {@link LateralQueryExpressionIndex} at execution time.
     */
    private boolean isCorrelated;

    /**
     * Table filters from the immediate enclosing query that this lateral
     * derived table depends on. Used to enforce correct join ordering so that
     * this lateral table is always evaluated after its dependencies.
     */
    private final HashSet<TableFilter> outerDependencies = new HashSet<>();

    /**
     * Create a derived table out of the given query.
     *
     * @param session the session
     * @param name the view name
     * @param columnTemplates column templates, or {@code null}
     * @param query the initialized query
     * @param topQuery the top level query
     */
    public DerivedTable(SessionLocal session, String name, Column[] columnTemplates, Query query, Query topQuery) {
        super(session.getDatabase().getMainSchema(), 0, name);
        setTemporary(true);
        this.topQuery = topQuery;
        DbException columnNotFound = null;
        try {
            query.prepareExpressions();
        } catch (DbException e) {
            if (e.getErrorCode() == ErrorCode.COLUMN_NOT_FOUND_1) {
                columnNotFound = e;
            } else {
                throw e;
            }
        }
        if (columnNotFound != null) {
            // A COLUMN_NOT_FOUND_1 error occurred during expression preparation.
            // This can be either:
            // (a) a genuine missing column (e.g., SELECT "x" FROM dual)
            // (b) a reference to a column in an outer query scope (a correlated
            //     derived table, e.g., WHERE outer_alias.col = inner_table.col)
            //
            // Distinguish them by examining the SELECT-list expressions:
            // - prepareExpressions() optimizes SELECT-list expressions FIRST.
            //   If the error is in a WHERE/condition (case b), all SELECT-list
            //   expressions will have been optimized and have known types.
            // - If the error is in the SELECT list itself (case a), the failing
            //   expression will have Value.UNKNOWN type.
            //
            // If any SELECT-list expression has an unknown type, it is a genuine
            // error; re-throw. Otherwise, treat as a correlated derived table.
            ArrayList<Expression> exprs = query.getExpressions();
            int colCount = query.getColumnCount();
            for (int i = 0; i < colCount; i++) {
                if (exprs.get(i).getType().getValueType() == Value.UNKNOWN) {
                    throw columnNotFound;
                }
            }
            isCorrelated = true;
        }
        try {
            this.querySQL = query.getPlanSQL(DEFAULT_SQL_FLAGS);
            originalParameters = query.getParameters();
            tables = new ArrayList<>(query.getTables());
            setColumns(initColumns(session, columnTemplates, query, true, false));
            viewQuery = query;
        } catch (DbException e) {
            if (e.getErrorCode() == ErrorCode.COLUMN_ALIAS_IS_NOT_SPECIFIED_1) {
                throw e;
            }
            e.addSQL(getCreateSQL());
            throw e;
        }
    }

    /**
     * Returns whether this derived table has correlated (outer query) column
     * references, i.e. is a lateral derived table.
     *
     * @return {@code true} if this is a correlated (lateral) derived table
     */
    public boolean isCorrelated() {
        return isCorrelated;
    }

    /**
     * Records an outer table filter that this lateral derived table depends on.
     * This is called when {@code mapColumns()} propagates an outer resolver
     * into this table's query and that resolver actually resolves columns there.
     *
     * @param outerFilter the outer table filter that must be evaluated before
     *                    this lateral derived table
     */
    public void addOuterDependency(TableFilter outerFilter) {
        outerDependencies.add(outerFilter);
    }

    /**
     * Returns the set of outer table filters (from the immediate parent query)
     * that this lateral derived table depends on.
     *
     * @return outer filter dependencies, possibly empty
     */
    public HashSet<TableFilter> getOuterDependencies() {
        return outerDependencies;
    }

    @Override
    protected QueryExpressionIndex createIndex(SessionLocal session, int[] masks) {
        if (isCorrelated) {
            return new LateralQueryExpressionIndex(this, viewQuery, originalParameters, session);
        }
        return new RegularQueryExpressionIndex(this, querySQL, originalParameters, session, masks);
    }

    @Override
    public boolean isQueryComparable() {
        return super.isQueryComparable()
            && (topQuery == null || topQuery.isEverything(ExpressionVisitor.QUERY_COMPARABLE_VISITOR));
    }

    @Override
    public long getMaxDataModificationId() {
        // Correlated (lateral) derived tables depend on the current row of the
        // enclosing query, which can change at any time. Always return MAX_VALUE
        // to prevent caching of result sets that contain this table.
        if (isCorrelated) {
            return Long.MAX_VALUE;
        }
        return super.getMaxDataModificationId();
    }

    @Override
    public boolean canDrop() {
        return false;
    }

    @Override
    public TableType getTableType() {
        return null;
    }

    @Override
    public Query getTopQuery() {
        return topQuery;
    }

    @Override
    public String getCreateSQL() {
        return null;
    }

    @Override
    public StringBuilder getSQL(StringBuilder builder, int sqlFlags) {
        return StringUtils.indent(builder.append("(\n"), querySQL, 4, true).append(')');
    }

    @Override
    public QueryScope getQueryScope() {
        return viewQuery.getOuterQueryScope();
    }

}
