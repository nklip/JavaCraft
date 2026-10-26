package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RestoreVerificationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String FINGERPRINT="a".repeat(64);

    @Test void publishedCommandsRequireTheExactOriginalPermanentEnvelope() throws Exception {
        JsonNode original=JSON.readTree("{\"messageId\":\"11\",\"resultMessageId\":\"12\",\"orderId\":\"1\"}");
        RestoreVerification.validatePublished(original,original.deepCopy());
        assertThrows(IOException.class,()->RestoreVerification.validatePublished(original,null));
        var changed=original.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("resultMessageId","13");
        assertThrows(IOException.class,()->RestoreVerification.validatePublished(original,changed));
    }

    @Test void terminalConfirmationRequiresMatchingPermanentDecision() throws Exception {
        RestoreVerification.validateDecision(order("CONFIRMED",null,false),decision("RECORDED",null,2));
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(order("CONFIRMED",null,false),null));
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(order("CONFIRMED",null,false),decision("RECORDED",null,99)));
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(order("CONFIRMED",null,false),decision("REJECTED","UNSUPPORTED_CURRENCY",2)));
    }

    @Test void rejectionRequiresCompletedCompensationAndPreservesTheOriginalReason() throws Exception {
        RestoreVerification.validateDecision(order("CANCELLED","UNSUPPORTED_CURRENCY",true),decision("REJECTED","UNSUPPORTED_CURRENCY",2));
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(order("CANCELLED","UNSUPPORTED_CURRENCY",false),decision("REJECTED","UNSUPPORTED_CURRENCY",2)));
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(order("CANCELLED","DIFFERENT_REASON",true),decision("REJECTED","UNSUPPORTED_CURRENCY",2)));
    }

    @Test void stockCancellationMustNotHaveReachedTheLedger() throws Exception {
        var stock=new RestoreVerification.Order(1,2,3,4,FINGERPRINT,"USD",BigDecimal.TEN,"CANCELLED","OUT_OF_STOCK",true,null);
        RestoreVerification.validateDecision(stock,null);
        assertThrows(IOException.class,()->RestoreVerification.validateDecision(stock,decision("RECORDED",null,2)));
    }

    private static RestoreVerification.Order order(String status,String reason,boolean released) throws Exception {
        var data=result(status.equals("CONFIRMED")?"RECORDED":"REJECTED",reason);
        var result=data.deepCopy(); result.put("messageId","12"); result.put("occurredAt","2026-10-26T12:00:00Z");
        return new RestoreVerification.Order(1,2,3,4,FINGERPRINT,"USD",BigDecimal.TEN,status,reason,released,result);
    }
    private static RestoreVerification.Decision decision(String outcome,String reason,long buyer) throws Exception {
        return new RestoreVerification.Decision(1,buyer,3,4,FINGERPRINT,"USD",BigDecimal.TEN,outcome,reason,JSON.createObjectNode().put("amount","10.00").put("currency","USD"),result(outcome,reason));
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode result(String outcome,String reason) {
        var result=JSON.createObjectNode().put("orderId","1").put("buyerId","2").put("sagaId","3").put("commandId","4")
                .put("requestFingerprint",FINGERPRINT).put("outcome",outcome);
        if(reason!=null) result.put("rejectionReason",reason);
        return result;
    }
}
