package dev.nklip.javacraft.xsd2model.service;

import jakarta.xml.bind.*;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Provide services for serialization and deserialization from Xml to Object and visa versa.
 * JAXBContext is thread safe and should only be created once and reused to avoid the cost of initializing the metadata multiple times.
 * Marshaller and Unmarshaller are not thread safe, but are lightweight to create and could be created per operation.
 */
public class JaxbService<T> {

    private final Class<T> clazz;
    private final JAXBContext context;

    public JaxbService(Class<T> clazz) throws JAXBException {
        this.clazz = clazz;
        context = JAXBContext.newInstance(this.clazz);
    }

    public String object2xml(T type) throws JAXBException {
        Writer writer = new StringWriter();

        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.marshal(type, writer);

        return writer.toString();
    }

    // Without @XmlRootElement annotation we can use this code instead of above
    public String object2xml2(T type) {
        Writer writer = new StringWriter();

        JAXB.marshal(type, writer);

        return writer.toString();
    }

    public T xml2object(String xml) throws JAXBException, XMLStreamException  {
        // xml to XmlStreamReader
        Reader reader = new StringReader(xml);
        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader xmlReader = factory.createXMLStreamReader(reader);

        Unmarshaller unmarshaller = context.createUnmarshaller();

        JAXBElement<T> jaxbElement = unmarshaller.unmarshal(xmlReader, this.clazz);

        return jaxbElement.getValue();
    }

}
