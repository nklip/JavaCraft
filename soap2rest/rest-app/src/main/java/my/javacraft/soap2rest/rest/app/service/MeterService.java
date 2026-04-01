package my.javacraft.soap2rest.rest.app.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import my.javacraft.soap2rest.rest.app.dao.AccountDao;
import my.javacraft.soap2rest.rest.app.dao.MeterDao;
import my.javacraft.soap2rest.rest.app.dao.entity.Meter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MeterService {

    private final MeterDao meterDao;
    private final AccountDao accountDao;

    public Meter createMeter(Long accountId, Meter submittedMeter) {
        if (!accountDao.existsById(accountId)) {
            throw new IllegalArgumentException("Account is not found.");
        }

        Meter meter = new Meter();
        meter.setAccountId(accountId);
        meter.setManufacturer(requireManufacturer(submittedMeter.getManufacturer()));
        return meterDao.save(meter);
    }

    public Meter updateMeter(Long accountId, Long meterId, Meter submittedMeter) {
        Meter existingMeter = meterDao.findByIdAndAccountId(meterId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Meter is not linked to account."));

        existingMeter.setManufacturer(requireManufacturer(submittedMeter.getManufacturer()));
        return meterDao.save(existingMeter);
    }

    public List<Meter> getMetersByAccountId(Long accountId) {
        return meterDao.findByAccountId(accountId);
    }

    public Meter getMeterByAccountIdAndMeterId(Long accountId, Long meterId) {
        return meterDao.findByIdAndAccountId(meterId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Meter is not linked to account."));
    }

    public int deleteAllByAccountId(Long accountId) {
        return meterDao.deleteByAccountId(accountId);
    }

    public int deleteByAccountIdAndMeterId(Long accountId, Long meterId) {
        return meterDao.deleteByIdAndAccountId(meterId, accountId);
    }

    private String requireManufacturer(String manufacturer) {
        if (manufacturer == null || manufacturer.isBlank()) {
            throw new IllegalArgumentException("Manufacturer is not provided.");
        }
        return manufacturer;
    }
}
