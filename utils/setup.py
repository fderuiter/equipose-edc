import sys
import subprocess
import socket
import os
import re
from urllib.parse import urlparse

def handle_validation_failure(tool_name, error_msg, required_version):
    if sys.stdin.isatty():
        print(f"Warning: {error_msg}")
        print(f"Required version for {tool_name} is {required_version}.")
        while True:
            resp = input("Do you wish to bypass this warning and continue? (y/n): ").strip().lower()
            if resp == 'y':
                return
            elif resp == 'n':
                print("Aborting setup.")
                sys.exit(1)
            else:
                print("Invalid input. Please enter 'y' or 'n'.")
    else:
        if os.environ.get("FORCE") == "1" or os.environ.get("OVERRIDE") == "1":
            print(f"Warning: {error_msg} (Bypassed due to override flag)")
            return
        print(f"Error: {error_msg}")
        print(f"Required version for {tool_name} is {required_version}.")
        sys.exit(1)

def check_java_version():
    required_version = "17"
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True, shell=(os.name == 'nt'))
        output = result.stderr + result.stdout
    except Exception:
        handle_validation_failure("Java", "Java is missing or not executable (or error running java -version).", required_version)
        return
        
    match = re.search(r'version "([^"]+)"', output)
    if not match:
        handle_validation_failure("Java", "Could not parse Java version from output.", required_version)
        return
    
    version_str = match.group(1)
    
    major_version = 0
    if version_str.startswith("1."):
        parts = version_str.split(".")
        if len(parts) > 1 and parts[1].isdigit():
            major_version = int(parts[1])
    else:
        parts = version_str.split(".")
        if len(parts) > 0 and parts[0].isdigit():
            major_version = int(parts[0])
            
    if major_version < 17:
        handle_validation_failure("Java", f"Installed Java version is {version_str}.", required_version)
        return
    print("Java version check passed.")

def check_maven_version():
    required_version = "3.0.0"
    try:
        result = subprocess.run(["mvn", "--version"], capture_output=True, text=True, check=True, shell=(os.name == 'nt'))
        output = result.stdout + result.stderr
    except Exception:
        handle_validation_failure("Maven", "Maven is missing or not executable.", required_version)
        return
        
    match = re.search(r'Apache Maven (\d+)\.(\d+)(?:\.(\d+))?', output)
    if not match:
        handle_validation_failure("Maven", "Could not parse Maven version.", required_version)
        return
        
    major = int(match.group(1))
    minor = int(match.group(2))
    patch = int(match.group(3)) if match.group(3) else 0
    
    if (major, minor, patch) < (3, 0, 0):
        version_str = f"{major}.{minor}.{patch}"
        handle_validation_failure("Maven", f"Installed Maven version is {version_str}.", required_version)
        return
    print("Maven version check passed.")

def check_docker_version():
    required_version = "19.03.0"
    try:
        result = subprocess.run(["docker", "--version"], capture_output=True, text=True, check=True, shell=(os.name == 'nt'))
        output = result.stdout + result.stderr
    except Exception:
        handle_validation_failure("Docker", "Docker is missing or not executable.", required_version)
        return
        
    match = re.search(r'Docker version (\d+)\.(\d+)(?:\.(\d+))?', output)
    if not match:
        handle_validation_failure("Docker", "Could not parse Docker version.", required_version)
        return
        
    major = int(match.group(1))
    minor = int(match.group(2))
    patch = int(match.group(3)) if match.group(3) else 0
    
    if (major, minor, patch) < (19, 3, 0):
        version_str = f"{major}.{minor}.{patch}"
        handle_validation_failure("Docker", f"Installed Docker version is {version_str}.", required_version)
        return
    print("Docker version check passed.")

def check_node_version():
    required_version = "22.13.0"
    try:
        result = subprocess.run(["node", "--version"], capture_output=True, text=True, check=True, shell=(os.name == 'nt'))
        output = result.stdout + result.stderr
    except Exception:
        handle_validation_failure("Node", "Node is missing or not executable.", required_version)
        return
        
    match = re.search(r'v?(\d+)\.(\d+)(?:\.(\d+))?', output)
    if not match:
        handle_validation_failure("Node", "Could not parse Node version.", required_version)
        return
        
    major = int(match.group(1))
    minor = int(match.group(2))
    patch = int(match.group(3)) if match.group(3) else 0
    
    if (major, minor, patch) < (22, 13, 0):
        version_str = f"{major}.{minor}.{patch}"
        handle_validation_failure("Node", f"Installed Node version is {version_str}.", required_version)
        return
    print("Node version check passed.")

def check_npm_version():
    required_version = "9.6.7"
    try:
        result = subprocess.run(["npm", "--version"], capture_output=True, text=True, check=True, shell=(os.name == 'nt'))
        output = result.stdout + result.stderr
    except Exception:
        handle_validation_failure("NPM", "NPM is missing or not executable.", required_version)
        return
        
    match = re.search(r'(\d+)\.(\d+)(?:\.(\d+))?', output)
    if not match:
        handle_validation_failure("NPM", "Could not parse NPM version.", required_version)
        return
        
    major = int(match.group(1))
    minor = int(match.group(2))
    patch = int(match.group(3)) if match.group(3) else 0
    
    if (major, minor, patch) < (9, 6, 7):
        version_str = f"{major}.{minor}.{patch}"
        handle_validation_failure("NPM", f"Installed NPM version is {version_str}.", required_version)
        return
    print("NPM version check passed.")

def verify_connection(host, port, service_name):
    try:
        port = int(port)
        s = socket.create_connection((host, port), timeout=5)
        s.close()
        return True
    except Exception as e:
        print(f"Immediate feedback: Connectivity error! Failed to connect to {service_name} at {host}:{port} ({e})")
        return False

def get_input(prompt, default_val, env_var_name):
    if env_var_name in os.environ:
        return os.environ[env_var_name]
    if not sys.stdin.isatty():
        return default_val
    try:
        val = input(prompt).strip()
        return val if val else default_val
    except EOFError:
        return default_val

def main():
    print("Welcome to the Guided Developer Setup!")
    check_java_version()
    check_maven_version()
    check_docker_version()
    check_node_version()
    check_npm_version()

    print("\nLet's configure your environment.")
    
    # Defaults
    env_vars = {}
    
    db_host = get_input("Database Host (default: localhost): ", "localhost", "DB_HOST")
    db_port = get_input("Database Port (default: 5432): ", "5432", "DB_PORT")
    db_user = get_input("Database User (default: clinica): ", "clinica", "DB_USER")
    db_pass = get_input("Database Password (default: clinica): ", "clinica", "DB_PASS")
    db_name = get_input("Database Name (default: clinica): ", "clinica", "DB")
    
    # immediate feedback
    if db_host not in ["db", "localhost", "127.0.0.1"]:
        print(f"Verifying connection to {db_host}:{db_port}...")
        if not verify_connection(db_host, db_port, "Database"):
            print(f"Warning: Failed to connect to Database at {db_host}:{db_port}. Proceeding anyway.")

    env_vars["DB_HOST"] = db_host
    env_vars["DB_PORT"] = db_port
    env_vars["DB_USER"] = db_user
    env_vars["DB_PASS"] = db_pass
    env_vars["DB"] = db_name
    env_vars["DB_TYPE"] = "postgres"

    ldap_val = get_input("Enable LDAP? (y/N): ", "N", "LDAP_ENABLED")
    ldap_enabled = ldap_val.lower() in ["y", "yes", "true", "1"]
    env_vars["LDAP_ENABLED"] = "true" if ldap_enabled else "false"
    if ldap_enabled:
        ldap_host = get_input("LDAP Host (e.g., ldap://localhost:389): ", "ldap://localhost:389", "LDAP_HOST")
        env_vars["LDAP_HOST"] = ldap_host
        env_vars["LDAP_USER_DN"] = get_input("LDAP User DN: ", "cn=admin,dc=localhost", "LDAP_USER_DN")
        env_vars["LDAP_PASSWORD"] = get_input("LDAP Password: ", "localhost", "LDAP_PASSWORD")
        
        parsed_url = urlparse(ldap_host)
        l_host = parsed_url.hostname or "localhost"
        l_port = parsed_url.port or 389
        if l_host not in ["localhost", "127.0.0.1"]:
            print(f"Verifying connection to {l_host}:{l_port}...")
            if not verify_connection(l_host, l_port, "LDAP"):
                print(f"Warning: Failed to connect to LDAP at {l_host}:{l_port}. Proceeding anyway.")

    # Resolve the project root dynamically using the script's physical location as the reference point
    project_root = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
    
    # Initialize default data directories relative to the resolved project root instead of utility subfolders
    default_data_path = os.path.join(project_root, "data")
    host_file_path = get_input(f"File Path for data (default: {default_data_path}): ", default_data_path, "HOST_FILE_PATH")
    env_vars["HOST_FILE_PATH"] = os.path.abspath(host_file_path).replace("\\", "/")
    env_vars["FILE_PATH"] = "/opt/clinica/data/"

    seed_val = get_input("Enable clinical data seeding? (y/N): ", "N", "SEED_CLINICAL_DATA")
    seed_clinical = seed_val.lower() in ["y", "yes", "true", "1"]
    env_vars["SEED_CLINICAL_DATA"] = "true" if seed_clinical else "false"

    # Create an automatic backup copy of any pre-existing environment configuration file at the project root
    # prior to performing any write actions
    env_path = os.path.join(project_root, ".env")
    if os.path.exists(env_path):
        import shutil
        backup_path = os.path.join(project_root, ".env.bak")
        shutil.copy2(env_path, backup_path)
        print(f"Backup copy successfully created: {backup_path}")

    if seed_clinical:
        default_template = os.path.join(project_root, "dummy_template.xlsx")
        template_path = get_input("Path to local Excel template: ", default_template, "HOST_CLINICAL_TEMPLATE_PATH")
        if not os.path.isfile(template_path):
            if sys.stdin.isatty() and not os.environ.get("HOST_CLINICAL_TEMPLATE_PATH"):
                print(f"Error: Template file not found at {template_path}")
                sys.exit(1)
            else:
                print(f"Warning: Template file not found at {template_path}. Proceeding anyway.")
        env_vars["HOST_CLINICAL_TEMPLATE_PATH"] = os.path.abspath(template_path).replace("\\", "/")
        env_vars["CLINICAL_TEMPLATE_PATH"] = "/opt/clinica/template.xlsx"
    else:
        dummy_path = os.path.join(project_root, "dummy_template.xlsx")
        with open(dummy_path, "w", newline='\n') as f:
            pass
        env_vars["HOST_CLINICAL_TEMPLATE_PATH"] = dummy_path.replace("\\", "/")
        env_vars["CLINICAL_TEMPLATE_PATH"] = ""

    # Directory structures
    os.makedirs(host_file_path, exist_ok=True)
    print(f"Created directory structure: {host_file_path}")

    with open(env_path, "w", newline='\n') as f:
        for k, v in env_vars.items():
            f.write(f"{k}={v}\n")
    
    print("\nSetup complete! You can now start the application.")

if __name__ == "__main__":
    main()
