package org.akaza.openclinica.logic.importdata;

import org.akaza.openclinica.bean.submit.crfdata.SubjectDataBean;
import org.akaza.openclinica.bean.submit.crfdata.UpsertOnBean;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

public class StreamingSubjectDataList extends ArrayList<SubjectDataBean> {

    private File xmlFile;
    private String xmlString;
    private String studyOid;
    private UpsertOnBean upsertOn;
    private int size = -1;
    
    private java.util.Map<String, Boolean> eventSignatureMap = new java.util.HashMap<>();
    private java.util.Map<String, Boolean> formSignatureMap = new java.util.HashMap<>();

    public StreamingSubjectDataList(File xmlFile) {
        this.xmlFile = xmlFile;
        initMetadata();
    }
    
    public StreamingSubjectDataList(String xmlString) {
        this.xmlString = xmlString;
        initMetadata();
    }
    
    public java.util.Map<String, Boolean> getEventSignatureMap() {
        return eventSignatureMap;
    }

    public java.util.Map<String, Boolean> getFormSignatureMap() {
        return formSignatureMap;
    }

    private InputStream getInputStream() throws Exception {
        if (xmlFile != null) return new FileInputStream(xmlFile);
        if (xmlString != null) return new ByteArrayInputStream(xmlString.getBytes("UTF-8"));
        throw new IllegalStateException("No data source");
    }

    private void initMetadata() {
        try (InputStream is = getInputStream()) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            XMLStreamReader reader = factory.createXMLStreamReader(is);
            
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    if ("ClinicalData".equals(reader.getLocalName())) {
                        studyOid = reader.getAttributeValue(null, "StudyOID");
                    } else if ("StudyEventDef".equals(reader.getLocalName())) {
                        String oid = reader.getAttributeValue(null, "OID");
                        String sigReq = reader.getAttributeValue(null, "SignatureRequired");
                        if ("Yes".equalsIgnoreCase(sigReq)) {
                            eventSignatureMap.put(oid, true);
                        }
                    } else if ("FormDef".equals(reader.getLocalName())) {
                        String oid = reader.getAttributeValue(null, "OID");
                        String sigReq = reader.getAttributeValue(null, "SignatureRequired");
                        if ("Yes".equalsIgnoreCase(sigReq)) {
                            formSignatureMap.put(oid, true);
                        }
                    } else if ("UpsertOn".equals(reader.getLocalName())) {
                        upsertOn = new UpsertOnBean();
                        String ns = reader.getAttributeValue(null, "NotStarted");
                        if (ns != null) upsertOn.setNotStarted("true".equalsIgnoreCase(ns) || "1".equals(ns));
                        String des = reader.getAttributeValue(null, "DataEntryStarted");
                        if (des != null) upsertOn.setDataEntryStarted("true".equalsIgnoreCase(des) || "1".equals(des));
                        String dec = reader.getAttributeValue(null, "DataEntryComplete");
                        if (dec != null) upsertOn.setDataEntryComplete("true".equalsIgnoreCase(dec) || "1".equals(dec));
                    } else if ("SubjectData".equals(reader.getLocalName())) {
                        break;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            throw new RuntimeException("Error reading metadata", e);
        }
    }

    public String getStudyOid() {
        return studyOid;
    }

    public UpsertOnBean getUpsertOn() {
        return upsertOn;
    }

    @Override
    public int size() {
        if (size == -1) {
            size = 0;
            try (InputStream is = getInputStream()) {
                XMLInputFactory factory = XMLInputFactory.newInstance();
                XMLStreamReader reader = factory.createXMLStreamReader(is);
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamReader.START_ELEMENT && "SubjectData".equals(reader.getLocalName())) {
                        size++;
                    }
                }
                reader.close();
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException("Error calculating dataset size: " + e.getMessage(), e);
            }
        }
        return size;
    }

    @Override
    public void forEach(java.util.function.Consumer<? super SubjectDataBean> action) {
        try (SubjectDataIterator it = new SubjectDataIterator(getInputStream())) {
            while (it.hasNext()) {
                action.accept(it.next());
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Error processing stream", e);
        }
    }

    public <R> R process(SubjectDataProcessor<R> processor) {
        try (SubjectDataIterator it = new SubjectDataIterator(getInputStream())) {
            while (it.hasNext()) {
                processor.process(it.next());
                if (processor.isStop()) {
                    return processor.getResult();
                }
            }
            return processor.getResult();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Error processing stream", e);
        }
    }

    @Override
    public Iterator<SubjectDataBean> iterator() {
        try {
            return new SubjectDataIterator(getInputStream());
        } catch (Exception e) {
            throw new RuntimeException("Error creating stream", e);
        }
    }
}
