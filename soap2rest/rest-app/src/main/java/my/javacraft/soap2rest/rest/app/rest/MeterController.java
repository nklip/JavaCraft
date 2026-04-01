package my.javacraft.soap2rest.rest.app.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.soap2rest.rest.app.dao.entity.Meter;
import my.javacraft.soap2rest.rest.app.service.MeterService;
import my.javacraft.soap2rest.utils.interceptor.ExecutionTime;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Tag(name = "Meter", description = "List of APIs for account meters")
@RequestMapping(path = "/api/v1/smart/{id}/meters")
@RequiredArgsConstructor
public class MeterController {

    private final MeterService meterService;

    @ExecutionTime
    @Operation(
            summary = "Get account meters",
            description = "API to get all meters for account"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Meter>> getMeters(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.getMetersByAccountId(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Get a meter",
            description = "API to get a single meter for account"
    )
    @GetMapping(value = "/{meterId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Meter> getMeter(@PathVariable("id") Long id, @PathVariable("meterId") Long meterId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.getMeterByAccountIdAndMeterId(id, meterId));
    }

    @ExecutionTime
    @Operation(
            summary = "Create a meter",
            description = "API to create a new meter for account"
    )
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Meter> createMeter(@PathVariable("id") Long id, @RequestBody Meter meter) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.createMeter(id, meter));
    }

    @ExecutionTime
    @Operation(
            summary = "Update a meter",
            description = "API to update a meter for account"
    )
    @PutMapping(value = "/{meterId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Meter> updateMeter(
            @PathVariable("id") Long id,
            @PathVariable("meterId") Long meterId,
            @RequestBody Meter meter) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.updateMeter(id, meterId, meter));
    }

    @ExecutionTime
    @Operation(
            summary = "Delete account meters",
            description = "API to delete all meters for account"
    )
    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> deleteAllMeters(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.deleteAllByAccountId(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Delete a meter",
            description = "API to delete one meter for account"
    )
    @DeleteMapping(value = "/{meterId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> deleteMeter(@PathVariable("id") Long id, @PathVariable("meterId") Long meterId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(meterService.deleteByAccountIdAndMeterId(id, meterId));
    }
}
