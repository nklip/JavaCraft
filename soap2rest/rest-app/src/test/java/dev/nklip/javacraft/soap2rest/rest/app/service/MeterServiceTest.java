package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.List;
import java.util.Optional;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.AccountRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.MeterRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.Meter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MeterServiceTest {

    @Mock
    MeterRepository meterRepository;

    @Mock
    AccountRepository accountRepository;

    @Test
    public void testCreateMeterUsesPathAccountId() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter submitted = new Meter();
        submitted.setAccountId(999L);
        submitted.setManufacturer("Landis + Gyr");
        when(accountRepository.existsById(1L)).thenReturn(true);
        when(meterRepository.save(any(Meter.class))).thenAnswer(inv -> inv.getArgument(0));

        Meter response = meterService.createMeter(1L, submitted);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(1L, response.getAccountId());
        Assertions.assertEquals("Landis + Gyr", response.getManufacturer());
        ArgumentCaptor<Meter> meterCaptor = ArgumentCaptor.forClass(Meter.class);
        verify(meterRepository).save(meterCaptor.capture());
        Assertions.assertEquals(1L, meterCaptor.getValue().getAccountId());
    }

    @Test
    public void testCreateMeterThrowsForMissingAccount() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter submitted = new Meter();
        submitted.setManufacturer("Landis + Gyr");
        when(accountRepository.existsById(1L)).thenReturn(false);

        Assertions.assertThrows(IllegalArgumentException.class, () -> meterService.createMeter(1L, submitted));
        verify(meterRepository, never()).save(any(Meter.class));
    }

    @Test
    public void testUpdateMeterUpdatesManufacturer() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter submitted = new Meter();
        submitted.setManufacturer("Updated Manufacturer");
        Meter existing = new Meter();
        existing.setId(100L);
        existing.setAccountId(1L);
        existing.setManufacturer("Old");
        when(meterRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.of(existing));
        when(meterRepository.save(any(Meter.class))).thenAnswer(inv -> inv.getArgument(0));

        Meter response = meterService.updateMeter(1L, 100L, submitted);

        Assertions.assertEquals("Updated Manufacturer", response.getManufacturer());
        Assertions.assertEquals(1L, response.getAccountId());
        verify(meterRepository).save(existing);
    }

    @Test
    public void testUpdateMeterThrowsForMissingMeter() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter submitted = new Meter();
        submitted.setManufacturer("Updated");
        when(meterRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.empty());

        Assertions.assertThrows(IllegalArgumentException.class, () -> meterService.updateMeter(1L, 100L, submitted));
        verify(meterRepository, never()).save(any(Meter.class));
    }

    @Test
    public void testGetMetersByAccountId() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter meter = new Meter();
        meter.setId(100L);
        when(meterRepository.findByAccountId(1L)).thenReturn(List.of(meter));

        List<Meter> response = meterService.getMetersByAccountId(1L);

        Assertions.assertEquals(1, response.size());
        Assertions.assertEquals(100L, response.getFirst().getId());
        verify(meterRepository).findByAccountId(1L);
    }

    @Test
    public void testGetMeterByAccountIdAndMeterId() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        Meter meter = new Meter();
        meter.setId(100L);
        when(meterRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.of(meter));

        Meter response = meterService.getMeterByAccountIdAndMeterId(1L, 100L);

        Assertions.assertEquals(100L, response.getId());
        verify(meterRepository).findByIdAndAccountId(100L, 1L);
    }

    @Test
    public void testGetMeterByAccountIdAndMeterIdThrowsForMissingMeter() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        when(meterRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.empty());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> meterService.getMeterByAccountIdAndMeterId(1L, 100L)
        );
    }

    @Test
    public void testDeleteAllByAccountId() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        when(meterRepository.deleteByAccountId(1L)).thenReturn(3);

        int deleted = meterService.deleteAllByAccountId(1L);

        Assertions.assertEquals(3, deleted);
        verify(meterRepository).deleteByAccountId(1L);
    }

    @Test
    public void testDeleteByAccountIdAndMeterId() {
        MeterService meterService = new MeterService(meterRepository, accountRepository);
        when(meterRepository.deleteByIdAndAccountId(100L, 1L)).thenReturn(1);

        int deleted = meterService.deleteByAccountIdAndMeterId(1L, 100L);

        Assertions.assertEquals(1, deleted);
        verify(meterRepository).deleteByIdAndAccountId(100L, 1L);
    }
}
