/* This is DB Compare Service class which Schematically compares the Databases enlisted by the user
 * 
 * Input: 
 */

package ae.cbd.compareDB.service;

import ae.cbd.compareDB.config.DatabaseConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;

@Service
public class DbCompareService {

    @Autowired
    private DatabaseConfig dbConf;

    private final DataSource dataSourceA;
    private final DataSource dataSourceB;
    private final DataCompareService dataCompareService;

    public DbCompareService(@Qualifier("dataSourceA") DataSource dataSourceA,
                            @Qualifier("dataSourceB") DataSource dataSourceB,
                            DataCompareService dataCompareService) {
        this.dataSourceA = dataSourceA;
        this.dataSourceB = dataSourceB;
        this.dataCompareService = dataCompareService;
    }

    public void compareSchemasAndWriteToFile(String outputPath) throws Exception {
        File outputFile = new File(outputPath);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write("===== DATABASE COMPARISON REPORT =====\n\n");

            try (Connection connA = dataSourceA.getConnection();
                 Connection connB = dataSourceB.getConnection()) {

                DatabaseMetaData metaA = connA.getMetaData();
                DatabaseMetaData metaB = connB.getMetaData();

                String dbAName = connA.getCatalog();
                String dbBName = connB.getCatalog();

                writer.write("Database A: " + dbAName + "\n");
                writer.write("Database B: " + dbBName + "\n\n");

                // Compare tables
                compareTables(metaA, metaB, connA, connB, writer);
            } catch (SQLException e) {
                writer.write("ERROR: Database connection error - " + e.getMessage() + "\n");
            }
        }
    }

    private void compareTables(DatabaseMetaData metaA, DatabaseMetaData metaB, Connection connA, Connection connB, BufferedWriter writer) throws SQLException, IOException {
        Set<String> tableSetA = getTables(metaA, dbConf.getDbAName());
        Set<String> tableSetB = getTables(metaB, dbConf.getDbBName());

        writer.write("===== TABLE COMPARISON =====\n");

        writer.write("Tables in Database A: " + tableSetA + "\n");
        writer.write("Tables in Database B: " + tableSetB + "\n\n");

        Set<String> missingInB = new HashSet<>(tableSetA);
        missingInB.removeAll(tableSetB);
        writer.write("Tables present in Database A but missing in Database B:\n" + formatSet(missingInB) + "\n");

        Set<String> missingInA = new HashSet<>(tableSetB);
        missingInA.removeAll(tableSetA);
        writer.write("Tables present in Database B but missing in Database A:\n" + formatSet(missingInA) + "\n");

        Set<String> commonTables = new HashSet<>(tableSetA);
        commonTables.retainAll(tableSetB);
        writer.write("\nCommon Tables: " + commonTables + "\n\n");

        compareColumns(metaA, metaB, commonTables, connA, connB, writer);
    }

    private void compareColumns(DatabaseMetaData metaA, DatabaseMetaData metaB, Set<String> commonTables, Connection connA, Connection connB, BufferedWriter writer) throws SQLException, IOException {
        writer.write("===== COLUMN COMPARISON =====\n");

        for (String table : commonTables) {
            writer.write("\nTable: " + table + "\n");

            Map<String, String> columnMapA = getColumnDetails(metaA, dbConf.getDbAName(), table);
            Map<String, String> columnMapB = getColumnDetails(metaB, dbConf.getDbBName(), table);

            Set<String> missingColumnsB = new HashSet<>(columnMapA.keySet());
            missingColumnsB.removeAll(columnMapB.keySet());
            writer.write("Columns in " + table + " (A) but missing in (B):\n" + formatSet(missingColumnsB) + "\n");

            Set<String> missingColumnsA = new HashSet<>(columnMapB.keySet());
            missingColumnsA.removeAll(columnMapA.keySet());
            writer.write("Columns in " + table + " (B) but missing in (A):\n" + formatSet(missingColumnsA) + "\n");

            Set<String> differentTypeColumns = new HashSet<>();
            for (String column : columnMapA.keySet()) {
                if (columnMapB.containsKey(column) && !columnMapA.get(column).equals(columnMapB.get(column))) {
                    differentTypeColumns.add(column + " (A: " + columnMapA.get(column) + " | B: " + columnMapB.get(column) + ")");
                }
            }
            writer.write("Columns with Different Data Types:\n" + formatSet(differentTypeColumns) + "\n");

            // If no structural differences, perform data comparison
            if (missingColumnsA.isEmpty() && missingColumnsB.isEmpty() && differentTypeColumns.isEmpty()) {
                writer.write("-> Proceeding with data comparison for table: " + table + "\n");
                String comparisonResult = dataCompareService.compareTableData(connA, connB, table);
                writer.write(comparisonResult + "\n");
                writer.write("\n");
            }
            writer.write("---------------------------------------------------\n");
        }
    }

    private Map<String, String> getColumnDetails(DatabaseMetaData metaData, String dbName, String table) throws SQLException {
        Map<String, String> columnMap = new HashMap<>();
        try (ResultSet columns = metaData.getColumns(dbName, null, table, "%")) {
            while (columns.next()) {
                columnMap.put(columns.getString("COLUMN_NAME"), columns.getString("TYPE_NAME"));
            }
        }
        return columnMap;
    }

    private Set<String> getTables(DatabaseMetaData metaData, String dbName) throws SQLException {
        Set<String> tableSet = new HashSet<>();
        try (ResultSet tables = metaData.getTables(dbName, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                tableSet.add(tables.getString("TABLE_NAME"));
            }
        }
        return tableSet;
    }

    private String formatSet(Set<String> set) {
        return set.isEmpty() ? "None\n" : String.join(", ", set) + "\n";
    }
    

}
