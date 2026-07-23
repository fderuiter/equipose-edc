import os
import shutil
import tempfile
import unittest
import subprocess
import sys

class TestSetupWizard(unittest.TestCase):
    def setUp(self):
        # Create a temporary directory structure mimicking the project root
        self.test_dir = tempfile.TemporaryDirectory()
        self.project_root = os.path.abspath(self.test_dir.name)
        self.utils_dir = os.path.join(self.project_root, "utils")
        os.makedirs(self.utils_dir, exist_ok=True)
        
        # Path to the actual setup.py and our copy under the temp directory
        self.actual_setup_py = os.path.join(os.path.dirname(os.path.abspath(__file__)), "setup.py")
        self.temp_setup_py = os.path.join(self.utils_dir, "setup.py")
        shutil.copy2(self.actual_setup_py, self.temp_setup_py)

    def tearDown(self):
        self.test_dir.cleanup()

    def test_setup_flow_with_root_resolution_and_backup(self):
        # Set environment variables for the test run to bypass prompts and checks
        test_env = os.environ.copy()
        test_env["FORCE"] = "1"
        test_env["DB_HOST"] = "localhost"
        test_env["LDAP_ENABLED"] = "N"
        test_env["SEED_CLINICAL_DATA"] = "N"
        # We don't set HOST_FILE_PATH, so it will use the default path relative to the project root

        # Run setup.py in the temporary environment for the first time
        result = subprocess.run(
            [sys.executable, self.temp_setup_py],
            capture_output=True,
            text=True,
            env=test_env
        )

        self.assertEqual(result.returncode, 0, f"setup.py failed with: {result.stderr}")
        
        # Verify active configuration is written to the project root (Requirement 2)
        expected_env_path = os.path.join(self.project_root, ".env")
        self.assertTrue(os.path.exists(expected_env_path), "Active configuration .env file not found at project root")
        
        # Verify subfolders do not contain .env
        subfolder_env_path = os.path.join(self.utils_dir, ".env")
        self.assertFalse(os.path.exists(subfolder_env_path), "Active configuration .env was incorrectly written to the utility subfolder")

        # Verify default data directory is created relative to the resolved project root (Requirement 4)
        expected_data_dir = os.path.join(self.project_root, "data")
        self.assertTrue(os.path.exists(expected_data_dir), "Default data directory not found at project root")
        self.assertTrue(os.path.isdir(expected_data_dir), "Default data directory is not a directory")

        # Verify default data directory is not in utility subfolder
        subfolder_data_dir = os.path.join(self.utils_dir, "data")
        self.assertFalse(os.path.exists(subfolder_data_dir), "Default data directory was incorrectly created inside the utility subfolder")

        # Verify template file dummy_template.xlsx is created relative to the resolved project root
        expected_template_path = os.path.join(self.project_root, "dummy_template.xlsx")
        self.assertTrue(os.path.exists(expected_template_path), "dummy_template.xlsx not found at project root")

        # Verify setup output did not mention backup (since .env didn't exist previously)
        self.assertNotIn("Backup copy successfully created", result.stdout)

        # Write custom content to the active configuration to test backup (Requirement 3)
        custom_content = "CUSTOM_VARIABLE_KEY=CUSTOM_VALUE_123\n"
        with open(expected_env_path, "w", encoding="utf-8") as f:
            f.write(custom_content)

        # Run setup.py a second time with the same environment variables
        result_second = subprocess.run(
            [sys.executable, self.temp_setup_py],
            capture_output=True,
            text=True,
            env=test_env
        )

        self.assertEqual(result_second.returncode, 0, f"Second run of setup.py failed with: {result_second.stderr}")

        # Verify that an automatic backup copy of the pre-existing configuration is created (Requirement 3)
        expected_backup_path = os.path.join(self.project_root, ".env.bak")
        self.assertTrue(os.path.exists(expected_backup_path), "Backup copy .env.bak was not created at project root")

        # Verify the backup has the custom content we wrote
        with open(expected_backup_path, "r", encoding="utf-8") as f:
            backup_content = f.read()
        self.assertEqual(backup_content, custom_content, "Backup content does not match the pre-existing configuration")

        # Verify silent safeguard confirmation output (Constraint: silent safeguard confirmation)
        self.assertIn("Backup copy successfully created", result_second.stdout)
        self.assertIn(expected_backup_path, result_second.stdout)

if __name__ == "__main__":
    unittest.main()
