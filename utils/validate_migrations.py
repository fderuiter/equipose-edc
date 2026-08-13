import os
import sys
import xml.etree.ElementTree as ET
import re

def get_local_name(tag):
    if tag.startswith('{'):
        return tag.split('}', 1)[1]
    return tag

def collect_core_tables(migration_dir, custom_dir):
    core_tables = set()
    for root, _, files in os.walk(migration_dir):
        # Only skip if we are strictly in the custom_dir or a subdirectory of it
        if root == custom_dir or root.startswith(custom_dir + os.sep):
            continue
        for file in files:
            if file.endswith('.xml'):
                filepath = os.path.join(root, file)
                try:
                    tree = ET.parse(filepath)
                    root_elem = tree.getroot()
                    for elem in root_elem.iter():
                        local_name = get_local_name(elem.tag)
                        if local_name == 'createTable':
                            table_name = elem.attrib.get('tableName')
                            if table_name:
                                core_tables.add(table_name.lower().strip())
                except Exception:
                    pass
    # Safety fallback of standard core tables
    safety_tables = {
        'audit_log_event', 'event_crf', 'crf_version', 'study', 'study_subject',
        'subject', 'user_account', 'dataset', 'filter', 'audit_log_event_type',
        'study_event', 'study_event_definition', 'crf', 'item', 'item_data', 'section'
    }
    core_tables.update(safety_tables)
    return core_tables

# Resolve default directories dynamically relative to the script location
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DEFAULT_MIGRATION_DIR = os.path.join(PROJECT_ROOT, 'core', 'src', 'main', 'resources', 'migration')
DEFAULT_CUSTOM_DIR = os.path.join(DEFAULT_MIGRATION_DIR, 'custom')

def check_migrations(migration_dir=None, custom_dir=None, exit_on_fail=True):
    if migration_dir is None:
        migration_dir = DEFAULT_MIGRATION_DIR
    if custom_dir is None:
        custom_dir = DEFAULT_CUSTOM_DIR

    # 1. Collect core tables
    core_tables = collect_core_tables(migration_dir, custom_dir)
    
    failed_changesets = []
    
    # We will traverse and perform all validations
    for root, _, files in os.walk(migration_dir):
        for file in files:
            if file.endswith('.xml'):
                filepath = os.path.join(root, file)
                
                # Check custom migration rules first if the file is strictly in custom_dir
                is_custom = (root == custom_dir or root.startswith(custom_dir + os.sep))
                if is_custom:
                    filename = os.path.basename(filepath)
                    if filename == 'release.xml':
                        continue
                    
                    # Verify required prefix in custom directory
                    if not (filename.startswith('ext_') or filename.startswith('custom_')):
                        print(f"FAILED: Custom migration file '{filename}' does not start with the required prefix.")
                        print("Custom extensions must use the required prefix ('ext_' or 'custom_') and reside in the dedicated directory.")
                        if exit_on_fail:
                            sys.exit(1)
                        else:
                            return False, f"Custom migration file '{filename}' does not start with the required prefix."
                
                try:
                    tree = ET.parse(filepath)
                except Exception as e:
                    print(f"FAILED: XML parsing error in {filepath}")
                    print(f"Details: {e}")
                    if exit_on_fail:
                        sys.exit(1)
                    else:
                        return False, f"XML parsing error in {filepath}: {e}"
                
                root_elem = tree.getroot()
                
                # Check for altering core system tables in custom migrations
                if is_custom:
                    for elem in root_elem.iter():
                        local_name = get_local_name(elem.tag)
                        
                        # Check structured alter table / alter column elements
                        if local_name in [
                            'addColumn', 'dropColumn', 'modifyDataType', 'renameColumn',
                            'dropTable', 'renameTable', 'addPrimaryKey', 'dropPrimaryKey',
                            'addUniqueConstraint', 'dropUniqueConstraint', 'addForeignKeyConstraint',
                            'dropForeignKeyConstraint', 'createIndex', 'dropIndex'
                        ]:
                            for attr in ['tableName', 'baseTableName', 'oldTableName', 'newTableName']:
                                val = elem.attrib.get(attr)
                                if val and val.lower().strip() in core_tables:
                                    print(f"FAILED: Custom migration {filepath} attempts to modify core table {val}.")
                                    if exit_on_fail:
                                        sys.exit(1)
                                    else:
                                        return False, f"Custom migration {filepath} attempts to modify core table {val}."
                        
                        # Check raw SQL element within custom migration
                        elif local_name == 'sql':
                            sql_text = (elem.text or '').lower()
                            for core_table in core_tables:
                                pattern = rf"\b(alter|drop|rename|truncate)\s+table\s+{re.escape(core_table)}\b"
                                if re.search(pattern, sql_text):
                                    print(f"FAILED: Custom migration {filepath} contains raw SQL that attempts to modify core table {core_table}.")
                                    if exit_on_fail:
                                        sys.exit(1)
                                    else:
                                        return False, f"Custom migration {filepath} contains raw SQL that attempts to modify core table {core_table}."
                
                # Original validation: check rollback paths for raw SQL blocks
                for child in root_elem:
                    if get_local_name(child.tag) == 'changeSet':
                        has_sql = False
                        has_rollback = False
                        
                        # Check descendants of this changeSet
                        for descendant in child.iter():
                            local_name = get_local_name(descendant.tag)
                            if local_name == 'sql':
                                has_sql = True
                            elif local_name == 'rollback':
                                has_rollback = True
                                
                        if has_sql and not has_rollback:
                            changeset_id = child.attrib.get('id', 'unknown')
                            failed_changesets.append(f"{filepath} (changeset: {changeset_id})")
                    
    if failed_changesets:
        print("FAILED: The following migrations lack rollback paths for raw SQL blocks:")
        for f in failed_changesets:
            print(f)
        if exit_on_fail:
            sys.exit(1)
        else:
            return False, "Lack rollback paths for raw SQL blocks"
    else:
        print("SUCCESS: All migration validations passed successfully.")
        if exit_on_fail:
            sys.exit(0)
        else:
            return True, "Success"

if __name__ == '__main__':
    check_migrations()
