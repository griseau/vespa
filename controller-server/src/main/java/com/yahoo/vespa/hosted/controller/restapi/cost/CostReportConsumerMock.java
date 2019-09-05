package com.yahoo.vespa.hosted.controller.restapi.cost;

import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.integration.resource.CostReportConsumer;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceAllocation;

import java.util.Map;
import java.util.function.Consumer;

/**
 * @author ldalves
 */
public class CostReportConsumerMock implements CostReportConsumer {

    private final Consumer<String> csvConsumer;
    private final Map<Property, ResourceAllocation> fixedAllocations;

    public CostReportConsumerMock() {
        this.csvConsumer = (ignored) -> {};
        this.fixedAllocations = Map.of();
    }

    public CostReportConsumerMock(Consumer<String> csvConsumer, Map<Property, ResourceAllocation> fixedAllocations) {
        this.csvConsumer = csvConsumer;
        this.fixedAllocations = Map.copyOf(fixedAllocations);
    }

    @Override
    public void consume(String csv) {
        csvConsumer.accept(csv);
    }

    @Override
    public Map<Property, ResourceAllocation> fixedAllocations() {
        return fixedAllocations;
    }

}
