import json
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

def merge_specs():
    base_spec = {
        "openapi": "3.0.0",
        "info": {
            "title": "Unified OpenClinica REST API",
            "version": "1.0.0",
            "description": "Interactive documentation for all OpenClinica REST endpoints."
        },
        "paths": {},
        "components": {}
    }

    specs_to_merge = [
        'core/external-api.json',
        'core/src/main/resources/randomize-api.yaml',
        'web/target/generated-docs/legacy-openapi.json',
        'modern/target/generated-docs/modern-openapi.json'
    ]

    for spec_path in specs_to_merge:
        if not os.path.exists(spec_path):
            print(f"Warning: {spec_path} not found. Skipping.")
            continue
            
        with open(spec_path, 'r', encoding='utf-8') as f:
            if spec_path.endswith('.yaml') or spec_path.endswith('.yml'):
                if yaml is None:
                    print(f"Warning: PyYAML is not installed. Skipping {spec_path}.")
                    continue
                spec = yaml.safe_load(f)
            else:
                spec = json.load(f)
                
            for path_key, path_val in spec.get('paths', {}).items():
                if path_key in base_spec['paths']:
                    print(f"Error: Duplicate path '{path_key}' found in '{spec_path}'.")
                    sys.exit(1)
                base_spec['paths'][path_key] = path_val
                
            for comp_type, comp_dict in spec.get('components', {}).items():
                if comp_type not in base_spec['components']:
                    base_spec['components'][comp_type] = {}
                for comp_key, comp_val in comp_dict.items():
                    if comp_key in base_spec['components'][comp_type]:
                        print(f"Error: Duplicate component '{comp_key}' found in components/{comp_type} in '{spec_path}'.")
                        sys.exit(1)
                    base_spec['components'][comp_type][comp_key] = comp_val

    os.makedirs('docs', exist_ok=True)
    with open('docs/openapi.json', 'w', encoding='utf-8') as f:
        json.dump(base_spec, f, indent=2)

if __name__ == "__main__":
    merge_specs()
