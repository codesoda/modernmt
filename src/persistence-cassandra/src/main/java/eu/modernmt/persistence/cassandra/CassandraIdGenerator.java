package eu.modernmt.persistence.cassandra;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.querybuilder.BuiltStatement;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import eu.modernmt.persistence.PersistenceException;

/**
 * This class provides static methods
 * that generate sequential integer IDs
 * for objects to store in our Cassandra DB.
 */
public class CassandraIdGenerator {

    /**
     * This method generates a new ID for a new object
     * that must be stored in a certain table.
     * The new IDs are long and are generated in a sequential way.
     * <p>
     * This method is thread-safe.
     *
     * @param connection the current connection with the database
     * @param tableId    the ID of the table in which we want to store a new
     * @return the newly generated ID,
     * @throws PersistenceException
     */
    public static long generate(CassandraConnection connection, int tableId) throws PersistenceException {
        /*the table COUNTERS_TABLE has a row for each other table in our db;
        each row holds the table id and a counter marking the last ID
        that has been employed when storing an object in that table.*/

        /*statement for getting the last ID used in the table under analysis
        * from the Counters_table*/
        BuiltStatement get = QueryBuilder.select("table_counter").
                from(CassandraDatabase.COUNTERS_TABLE).
                where(QueryBuilder.eq("table_id", tableId));


        /*Read the last ID used in the table under analysis.
         If it is still the same, increment it and
         return the new incremented ID.
         Otherwise, read again.
         This technique lets us read and update the ID atomically,
          so it is thread-safe (even it may be if a bit slow).*/
        while (true) {

            /* Get the the last ID used in the table under analysis*/
            long oldCount = CassandraUtils.checkedExecute(connection, get).one().getLong("table_counter");

            /* Statement for updating the last ID only if it is still the same*/
            BuiltStatement set = QueryBuilder.update(CassandraDatabase.COUNTERS_TABLE).
                    with(QueryBuilder.set("table_counter", (oldCount + 1L))).
                    where(QueryBuilder.eq("table_id", tableId)).
                    onlyIf(QueryBuilder.eq("table_counter", oldCount));

            /* Try to execute the statement; if it succeeded,
            * then it means that no-one has updated the last ID
            * after this thread has read it, so it can use it*/
            if (CassandraUtils.checkedExecute(connection, set).wasApplied())
                return oldCount + 1L;
        }
    }

    /**
     * This method updates the current domains counter to a given value
     * if it is greater than the current value
     *
     * @param connection the current connection with the database
     * @param newCounter the new domains counter (if it is greater than the current one)
     * @throws PersistenceException
     */
    public static void advanceDomainsCounter(CassandraConnection connection, int newCounter) throws PersistenceException {

        /* Statement for updating the last ID only if it smaller than the new counter*/
        BuiltStatement built = QueryBuilder.update(CassandraDatabase.COUNTERS_TABLE).
                with(QueryBuilder.set("table_counter", newCounter)).
                where(QueryBuilder.eq("table_id", CassandraDatabase.DOMAINS_TABLE_ID)).
                onlyIf(QueryBuilder.lte("table_counter", newCounter));
        try {
            connection.session.execute(built);
        } catch (NoHostAvailableException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * This method puts in the counters_table a new entry for each table
     * created during the database initialization
     *
     * @param connection the current connection with the database
     * @param tableIds   the IDs of the tables in the DB
     * @throws PersistenceException
     */
    public static void initializeTableCounter(CassandraConnection connection, int[] tableIds) throws PersistenceException {

        for (int table_id : tableIds) {
            String statement =
                    "INSERT INTO " + CassandraDatabase.COUNTERS_TABLE +
                            " (table_id, table_counter) VALUES (" + table_id + ", 0);";
            try {
                connection.session.execute(statement);
            } catch (NoHostAvailableException e) {
                throw new PersistenceException(e);
            }
        }
    }
}