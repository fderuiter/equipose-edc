import os
import sys
import subprocess

try:
    import yaml
except ImportError:
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "pyyaml", "--quiet"])
        import yaml
    except Exception:
        yaml = None

if yaml is not None:
    def env_constructor(loader, node):
        if isinstance(node, yaml.ScalarNode):
            return loader.construct_scalar(node)
        elif isinstance(node, yaml.SequenceNode):
            return [loader.construct_object(child) for child in node.value]
        return None

    yaml.SafeLoader.add_constructor("!ENV", env_constructor)

def extract_nav_paths(nav_item, paths):
    """Recursively extract file paths from the mkdocs nav structure."""
    if isinstance(nav_item, list):
        for item in nav_item:
            extract_nav_paths(item, paths)
    elif isinstance(nav_item, dict):
        for key, value in nav_item.items():
            if isinstance(value, str):
                paths.add(value)
            else:
                extract_nav_paths(value, paths)
    elif isinstance(nav_item, str):
        paths.add(nav_item)

def is_restricted(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            first_line = f.readline().strip()
            if first_line != '---':
                return False
            fm_lines = []
            for line in f:
                if line.strip() == '---':
                    break
                fm_lines.append(line)
            fm = yaml.safe_load(''.join(fm_lines)) or {}
            return fm.get('visibility') == 'restricted'
    except Exception:
        return False

def main():
    mkdocs_file = 'mkdocs.yml'
    docs_dir = 'docs'

    if not os.path.exists(mkdocs_file):
        print(f"Error: {mkdocs_file} not found.")
        sys.exit(1)

    with open(mkdocs_file, 'r') as f:
        try:
            config = yaml.safe_load(f)
        except yaml.YAMLError as e:
            print(f"Error parsing {mkdocs_file}: {e}")
            sys.exit(1)

    nav = config.get('nav', [])
    nav_paths = set()
    extract_nav_paths(nav, nav_paths)

    # Convert nav paths to match relative paths in docs dir
    # nav paths in mkdocs.yml are relative to the docs directory
    
    physical_md_files = set()
    for root, dirs, files in os.walk(docs_dir):
        # Skip auto-generated frontend API documentation to avoid false positives
        if 'frontend-api' in root:
            continue
        for file in files:
            if file.endswith('.md'):
                filepath = os.path.join(root, file)
                if is_restricted(filepath):
                    continue
                # Get path relative to docs directory
                rel_path = os.path.relpath(filepath, docs_dir)
                physical_md_files.add(rel_path)

    orphaned_files = physical_md_files - nav_paths

    if orphaned_files:
        print("Error: The following markdown files are orphaned (not referenced in mkdocs.yml nav):")
        for file in sorted(orphaned_files):
            print(f"  - {file}")
        sys.exit(1)
    else:
        print("Success: All markdown files are referenced in the navigation configuration.")
        sys.exit(0)

if __name__ == '__main__':
    main()
