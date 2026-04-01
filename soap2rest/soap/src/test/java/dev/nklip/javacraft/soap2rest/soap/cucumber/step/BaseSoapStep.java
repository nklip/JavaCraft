package dev.nklip.javacraft.soap2rest.soap.cucumber.step;

import jakarta.xml.soap.MessageFactory;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest.Body;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;

abstract class BaseSoapStep {

    public DSResponse sendSoapRequest(int port, Body body) throws Exception {
        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);

        WebServiceTemplate webServiceTemplate = createWebServiceTemplate();

        return (DSResponse) webServiceTemplate.marshalSendAndReceive(
                "http://localhost:" + port + "/soap2rest/soap/v1/DeliverServiceWS.wsdl",
                dsRequest
        );
    }

    private WebServiceTemplate createWebServiceTemplate() throws Exception {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory(MessageFactory.newInstance());
        messageFactory.afterPropertiesSet();

        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("dev.nklip.javacraft.soap2rest.soap.generated.ds.ws");
        marshaller.afterPropertiesSet();

        WebServiceTemplate webServiceTemplate = new WebServiceTemplate(messageFactory);
        webServiceTemplate.setMarshaller(marshaller);
        webServiceTemplate.setUnmarshaller(marshaller);
        webServiceTemplate.afterPropertiesSet();

        return webServiceTemplate;
    }
}
