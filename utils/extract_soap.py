import os
import glob

def generate_soap_docs():
    schema_dir = 'ws/src/main/webapp/WEB-INF/schemas/'
    if not os.path.exists(schema_dir):
        print(f"Warning: SOAP schema directory '{schema_dir}' does not exist. Skipping SOAP doc generation.")
        return
    schema_files = glob.glob(os.path.join(schema_dir, '*.xsd'))
    
    docs_dir = 'docs/reference'
    os.makedirs(docs_dir, exist_ok=True)
    
    with open(os.path.join(docs_dir, 'soap.md'), 'w', encoding='utf-8') as out:
        out.write('# Static SOAP Service Documentation\n\n')
        out.write('This section provides static references to the legacy SOAP service specifications.\n\n')
        
        for xsd_file in sorted(schema_files):
            filename = os.path.basename(xsd_file)
            out.write(f'## {filename}\n\n')
            out.write('```xml\n')
            with open(xsd_file, 'r', encoding='utf-8') as f:
                out.write(f.read())
            out.write('\n```\n\n')

if __name__ == "__main__":
    generate_soap_docs()
