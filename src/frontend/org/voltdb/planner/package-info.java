/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * <p>This package contains the cost-based, flexible, distributed planner/optimizer for VoltDB.</p>
 *
 * <p>
 * The execution of any query is a some kind of a scan.  By <em>scan</em> we mean a loop,
 * or perhaps a nested loop.  The most naive, and slowest, scan would be just a scan of a table.
 * Each row of the table is fetched, evaluated and written to the output or else skipped, depending
 * the filters of join conditions or the WHERE clause.  A more sophisticated method would be
 * to scan an index.  This scans only a fraction of the rows.  But it's still a scan.</p>
 *
 * <p>
 * A nested scan is used for joins.  These scan two tables, the left-hand, or <em>inner</em> table
 * and the right hand, or <em>inner</em> table.  For each pair of rows the nested scan
 * evaluates join conditions or WHERE conditions and either processes the pair of rows
 * or else skips one of them.  There are optimizations which can skip all inner rows for
 * a given outer row, or which can use indices to reduce the number of rows considered.</p>
 *
 * <p>
 * The planner creates <em>plans</em>.  A plan is a kind of description of the execution of
 * a query.  It may help to think of a plan as an executable program file, say an ELF or
 * a.out file.  The plan contains the instructions needed to do the computation, but the
 * plan doesn't have any resources to do any calculations.  The plan is just a data structure.</p>
 *
 * <p>
 * Each plan is made of a tree of <em>plan nodes</em>.  Each plan node describes
 * some computation which operates on tables and produces a single table.  The result table
 * and the argument tables may be persistent tables or they may be temporary tables.  Temporary
 * tables created during the execution of a plan are destroyed after the execution of the
 * plan.</p>
 *
 * <p>
 * For example, a SEQUENTIAL_SCAN node describes a computation which operates on a
 * table, reads each row of the table and sends it to the output, possibly filtering
 * with a predicate or applying a limit or an offset.  A NESTED_LOOP plan node describes
 * a join computation.  This computation will be given two tables which must be scanned
 * in two nested loops.  For each row of the left hand table, which we call the <em>outer</em>
 * table, we look at each row of the right hand table, which we call the <em>inner</em>
 * table.  If the pair of rows satisfy the join condition, we write the row to the
 * output table.</br>
 * Note:  The computation for NESTED_LOOP is somewhat more complicated
 * than this, as we may be able to skip the scan of the inner table, and there
 * may be other optimizations.  We are just giving the flavor of the computation here.</br>
 * Note Also: The tree formed by a plan is not very similar to the parse tree formed
 * from a SQL or DDL statement.  The plan relates to the SQL text approximately the
 * same way an assembly language program relates to a string of C++ program text.</p>
 *
 * <p>
 * We say a plan node <em>describes</em> a computation because the plan node does
 * not actually have the code to do the computation.  Plan nodes are generated by
 * the planner in the Java code in the VoltDB server.  Plans are transmitted to the
 * Execution Engine, or EE, in the form of JSON objects, represented as strings.
 * The EE has its own definitions for all the plan nodes in C++.  The
 * EE can reconstruct the the plan in a C++ data structure from the JSON.  The EE also
 * has a hierarchy of classes called <em>executor classes</em> which is parallel to
 * the plan node hierarchy.  Executor class objects contain the code which does
 * the real computations of the plan nodes.  Most dynamic data, such as table
 * data, are stored in the executor objects.</p>
 *
 * <p>
 * The children of a plan
 * node describe computations which produce temporary tables.  The parents of a plan
 * node describe computations which consume the result of the plan node.  So, child
 * nodes could also be called <em>producer</em> nodes, and parent nodes could also
 * be called <em>consumer</em> nodes.</br>
 * Note: A node may have only one parent node.  A node may, therefore, create only
 * one value.</p>
 *
 * <p>The output schema of a plan node gives the set of columns which the computation
 * of the plan node will create.  For each row which is not rejected, some computation
 * is done to the row, and the results are put into an output row.  The output
 * row has the output schema.  An output schema itself may contain expressions to
 * transform the data.  For example in the statement
 * <pre>
 *   select T.C, sqrt(T.C) from T;
 * </pre>
 * the output schema might be something like
 * <pre>
 *   <{type: float, expression: C}, {type: float, expression: sqrt(C)}>.
 * </pre>
 * This would mean the first output column is C, as a floating point value,
 * and the second is sqrt(C), also as a floating point value.  Note that the
 * column C in T does not have to be a floating point value.  It could be an
 * integer which would be implicitly converted to float.
 * <p>
 * Some operations can be computed on a row-by-row basis.  For example, if
 * a table has N rows, but only M < N rows are used in the plan, the projection
 * from N rows to M rows happens independently for all rows.  Also, most
 * aggregate functions can be computed row-by-row.  For example, the sum
 * aggregate function needs only a running sum and values from the current row
 * to update its state.  Other operations, such as a join, may take access to
 * an entire table.  For row-by-row operations, we avoid creating extra temporary
 * tables by combining scans. The row-by-row operations are placed in <em>inline</em>
 * nodes in a scan node.  Each row of the scan which passes the filters are
 * applied to the inline nodes during the scan.  The inline nodes have
 * their own output columns, as discussed above.</p>
 *
 * <p>
 * The classes of plan nodes are organized in a hierarchy.  The root of the
 * hierarchy is the AbstractPlanNode class.
 * </p>
 */
package org.voltdb.planner;
