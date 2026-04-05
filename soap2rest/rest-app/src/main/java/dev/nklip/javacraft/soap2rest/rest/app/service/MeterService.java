package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.AccountRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.repository.MeterRepository;
import dev.nklip.javacraft.soap2rest.rest.app.persistence.entity.Meter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MeterService {

    private final MeterRepository meterRepository;
    private final AccountRepository accountRepository;

    public Meter createMeter(Long accountId, Meter submittedMeter) {
        if (!accountRepository.existsById(accountId)) {
            throw new IllegalArgumentException("Account is not found.");
        }

        Meter meter = new Meter();
        meter.setAccountId(accountId);
        meter.setManufacturer(requireManufacturer(submittedMeter.getManufacturer()));
        return meterRepository.save(meter);
    }

    public Meter updateMeter(Long accountId, Long meterId, Meter submittedMeter) {
        Meter existingMeter = meterRepository.findByIdAndAccountId(meterId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Meter is not linked to account."));

        existingMeter.setManufacturer(requireManufacturer(submittedMeter.getManufacturer()));
        return meterRepository.save(existingMeter);
    }

    public List<Meter> getMetersByAccountId(Long accountId) {
        return meterRepository.findByAccountId(accountId);
    }

    public Meter getMeterByAccountIdAndMeterId(Long accountId, Long meterId) {
        return meterRepository.findByIdAndAccountId(meterId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Meter is not linked to account."));
    }

    public int deleteAllByAccountId(Long accountId) {
        return meterRepository.deleteByAccountId(accountId);
    }

    public int deleteByAccountIdAndMeterId(Long accountId, Long meterId) {
        return meterRepository.deleteByIdAndAccountId(meterId, accountId);
    }

    private String requireManufacturer(String manufacturer) {
        if (manufacturer == null || manufacturer.isBlank()) {
            throw new IllegalArgumentException("Manufacturer is not provided.");
        }
        return manufacturer;
    }
}
