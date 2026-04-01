package dev.nklip.javacraft.bdd.cucumber.step;

import dev.nklip.javacraft.bdd.service.BMIService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;

@Slf4j
public class BMIStepDefinitions {

    private final BMIService bmiService;

    private BigDecimal actualBmi;

    public BMIStepDefinitions(BMIService bmiService) {
        this.bmiService = bmiService;
    }

    @Given("the BMI calculator is available")
    public void theBmiCalculatorIsAvailable() {
        Assertions.assertNotNull(bmiService, "BMIService should be injected");
    }

    @When("a person has weight = {string} kg and height = {string} metres")
    public void calculateBmiInMetric(String weight, String height) {
        this.actualBmi = bmiService.calculate(new BigDecimal(weight), new BigDecimal(height), false);
    }

    @When("a person has weight = {string} lbs and height = {string} inches")
    public void calculateBmiInImperial(String weight, String height) {
        this.actualBmi = bmiService.calculate(new BigDecimal(weight), new BigDecimal(height), true);
    }

    @Then("that person should have BMI = {string} kg m2")
    @Then("that person should have BMI = {string}")
    public void checkBMI(String expectedBmi) {
        log.info("checking actual BMI is '{}'", expectedBmi);
        Assertions.assertEquals(new BigDecimal(expectedBmi), actualBmi);
    }

    @When("we use batch to test BMI calculator")
    public void batchTestBMICalculator(DataTable table) {
        List<List<String>> rows = table.cells();
        for (List<String> row : rows) {
            String weight = row.get(0);
            String height = row.get(1);
            boolean isImperial = Boolean.parseBoolean(row.get(2));
            String expected = row.get(3);

            BigDecimal actual = bmiService.calculate(new BigDecimal(weight), new BigDecimal(height), isImperial);

            Assertions.assertEquals(new BigDecimal(expected), actual);
        }
    }

    @When("we classify BMI values into categories")
    public void classifyBmiValuesIntoCategories(DataTable table) {
        List<List<String>> rows = table.cells();
        for (List<String> row : rows) {
            BigDecimal bmi = new BigDecimal(row.getFirst().trim());
            String expectedCategory = row.get(1).trim();

            Assertions.assertEquals(expectedCategory, bmiService.bmiToCategory(bmi),
                    "Category mismatch for BMI " + bmi);
        }
    }

}
