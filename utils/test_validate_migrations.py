import os
import tempfile
import unittest
import sys

# Add the utils directory to the path so we can import validate_migrations
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import validate_migrations

class TestValidateMigrations(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.TemporaryDirectory()
        self.migration_dir = os.path.join(self.test_dir.name, "migration")
        self.custom_dir = os.path.join(self.migration_dir, "custom")
        os.makedirs(self.custom_dir, exist_ok=True)
        
    def tearDown(self):
        self.test_dir.cleanup()

    def create_migration_file(self, subdir, filename, content):
        dir_path = os.path.join(self.migration_dir, subdir) if subdir else self.migration_dir
        os.makedirs(dir_path, exist_ok=True)
        filepath = os.path.join(dir_path, filename)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return filepath

    def test_get_local_name(self):
        self.assertEqual(validate_migrations.get_local_name("{http://www.liquibase.org/xml/ns/dbchangelog/1.9}changeSet"), "changeSet")
        self.assertEqual(validate_migrations.get_local_name("changeSet"), "changeSet")

    def test_collect_core_tables(self):
        # Create a core migration that defines a table "study_user"
        self.create_migration_file("3.0", "core_mig.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="1" author="test">
        <createTable tableName="study_user">
            <column name="id" type="int"/>
        </createTable>
    </changeSet>
</databaseChangeLog>
""")
        core_tables = validate_migrations.collect_core_tables(self.migration_dir, self.custom_dir)
        self.assertIn("study_user", core_tables)
        # Fallback table should also be present
        self.assertIn("event_crf", core_tables)

    def test_custom_migration_valid_prefix_ext(self):
        # Create a core migration defining core_table
        self.create_migration_file("3.0", "core.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="1" author="test">
        <createTable tableName="core_table"/>
    </changeSet>
</databaseChangeLog>
""")
        # Create a custom migration with 'ext_' prefix that creates a custom table
        self.create_migration_file("custom", "ext_my_table.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="c1" author="test">
        <createTable tableName="ext_my_table"/>
    </changeSet>
</databaseChangeLog>
""")
        success, msg = validate_migrations.check_migrations(self.migration_dir, self.custom_dir, exit_on_fail=False)
        self.assertTrue(success, msg)

    def test_custom_migration_valid_prefix_custom(self):
        # Create a custom migration with 'custom_' prefix that creates a custom table
        self.create_migration_file("custom", "custom_my_table.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="c1" author="test">
        <createTable tableName="custom_my_table"/>
    </changeSet>
</databaseChangeLog>
""")
        success, msg = validate_migrations.check_migrations(self.migration_dir, self.custom_dir, exit_on_fail=False)
        self.assertTrue(success, msg)

    def test_custom_migration_invalid_prefix(self):
        # Create custom migration with invalid prefix
        self.create_migration_file("custom", "invalid_name.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="c1" author="test">
        <createTable tableName="my_table"/>
    </changeSet>
</databaseChangeLog>
""")
        success, msg = validate_migrations.check_migrations(self.migration_dir, self.custom_dir, exit_on_fail=False)
        self.assertFalse(success)
        self.assertIn("does not start with the required prefix", msg)

    def test_custom_migration_modifies_core_table_structured(self):
        # Create core migration defining "core_table"
        self.create_migration_file("3.0", "core.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="1" author="test">
        <createTable tableName="core_table"/>
    </changeSet>
</databaseChangeLog>
""")
        # Create custom migration attempting to alter "core_table" via XML tags
        self.create_migration_file("custom", "ext_hack.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="c1" author="test">
        <addColumn tableName="core_table">
            <column name="bad_col" type="varchar"/>
        </addColumn>
    </changeSet>
</databaseChangeLog>
""")
        success, msg = validate_migrations.check_migrations(self.migration_dir, self.custom_dir, exit_on_fail=False)
        self.assertFalse(success)
        self.assertIn("attempts to modify core table", msg)

    def test_custom_migration_modifies_core_table_sql(self):
        # Create custom migration attempting to alter "event_crf" via raw SQL
        self.create_migration_file("custom", "ext_hack_sql.xml", """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="c1" author="test">
        <sql>
            ALTER TABLE event_crf ADD COLUMN dummy varchar(255);
        </sql>
        <rollback>
            ALTER TABLE event_crf DROP COLUMN dummy;
        </rollback>
    </changeSet>
</databaseChangeLog>
""")
        success, msg = validate_migrations.check_migrations(self.migration_dir, self.custom_dir, exit_on_fail=False)
        self.assertFalse(success)
        self.assertIn("attempts to modify core table", msg)

if __name__ == '__main__':
    unittest.main()
