package dev.nklip.javacraft.bdd.cucumber.step;

import dev.nklip.javacraft.bdd.service.BMIService;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;

public class BMIStepDefinitions {

    private final BMIService bmiService;

    private BigDecimal actualBmi;
    private String actualCategory;

    public BMIStepDefinitions(BMIService bmiService) {
        this.bmiService = bmiService;
    }

    @Given("a person has a BMI of {string}")
    public void setBmi(String bmi) {
        this.actualBmi = new BigDecimal(bmi);
    }

    @When("a person calculates their BMI from {string} kg and {string} metres")
    public void calculateBmiInMetric(String weight, String height) {
        this.actualBmi = bmiService.calculate(new BigDecimal(weight), new BigDecimal(height), false);
    }

    @When("a person calculates their BMI from {string} lbs and {string} inches")
    public void calculateBmiInImperial(String weight, String height) {
        this.actualBmi = bmiService.calculate(new BigDecimal(weight), new BigDecimal(height), true);
    }

    @When("they check their BMI category")
    public void classifyBmi() {
        this.actualCategory = bmiService.bmiToCategory(actualBmi);
    }

    @Then("their BMI should be {string}")
    public void checkBmi(String expectedBmi) {
        Assertions.assertEquals(new BigDecimal(expectedBmi), actualBmi);
    }

    @Then("their BMI category should be {string}")
    public void checkCategory(String expectedCategory) {
        Assertions.assertEquals(expectedCategory, actualCategory);
    }

}
