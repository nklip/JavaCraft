package my.javacraft.soap2rest.rest.app.rest;

import java.util.ArrayList;
import java.util.List;
import my.javacraft.soap2rest.rest.app.dao.entity.Meter;
import my.javacraft.soap2rest.rest.app.service.MeterService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MeterControllerTest {

    @Mock
    MeterService meterService;

    @Test
    public void testGetMeters() {
        MeterController meterController = new MeterController(meterService);
        when(meterService.getMetersByAccountId(1L)).thenReturn(createMeterList());

        ResponseEntity<List<Meter>> response = meterController.getMeters(1L);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(1, response.getBody().size());
        verify(meterService).getMetersByAccountId(1L);
    }

    @Test
    public void testGetMeter() {
        MeterController meterController = new MeterController(meterService);
        when(meterService.getMeterByAccountIdAndMeterId(1L, 100L)).thenReturn(createMeter());

        ResponseEntity<Meter> response = meterController.getMeter(1L, 100L);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(100L, response.getBody().getId());
        verify(meterService).getMeterByAccountIdAndMeterId(1L, 100L);
    }

    @Test
    public void testCreateMeter() {
        MeterController meterController = new MeterController(meterService);
        Meter submitted = new Meter();
        submitted.setManufacturer("Landis + Gyr");
        when(meterService.createMeter(1L, submitted)).thenReturn(createMeter());

        ResponseEntity<Meter> response = meterController.createMeter(1L, submitted);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(100L, response.getBody().getId());
        verify(meterService).createMeter(1L, submitted);
    }

    @Test
    public void testUpdateMeter() {
        MeterController meterController = new MeterController(meterService);
        Meter submitted = new Meter();
        submitted.setManufacturer("Updated");
        Meter updated = createMeter();
        updated.setManufacturer("Updated");
        when(meterService.updateMeter(1L, 100L, submitted)).thenReturn(updated);

        ResponseEntity<Meter> response = meterController.updateMeter(1L, 100L, submitted);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("Updated", response.getBody().getManufacturer());
        verify(meterService).updateMeter(1L, 100L, submitted);
    }

    @Test
    public void testDeleteAllMeters() {
        MeterController meterController = new MeterController(meterService);
        when(meterService.deleteAllByAccountId(1L)).thenReturn(3);

        ResponseEntity<Integer> response = meterController.deleteAllMeters(1L);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(3, response.getBody());
        verify(meterService).deleteAllByAccountId(1L);
    }

    @Test
    public void testDeleteMeter() {
        MeterController meterController = new MeterController(meterService);
        when(meterService.deleteByAccountIdAndMeterId(1L, 100L)).thenReturn(1);

        ResponseEntity<Integer> response = meterController.deleteMeter(1L, 100L);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(1, response.getBody());
        verify(meterService).deleteByAccountIdAndMeterId(1L, 100L);
    }

    private Meter createMeter() {
        Meter meter = new Meter();
        meter.setId(100L);
        meter.setAccountId(1L);
        meter.setManufacturer("Landis + Gyr");
        return meter;
    }

    private List<Meter> createMeterList() {
        List<Meter> list = new ArrayList<>();
        list.add(createMeter());
        return list;
    }
}
