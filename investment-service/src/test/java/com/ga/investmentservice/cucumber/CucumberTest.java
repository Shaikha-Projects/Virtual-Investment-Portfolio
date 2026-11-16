package com.ga.investmentservice.cucumber;

import org.junit.platform.suite.api.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/AssetAPI.feature")
@ConfigurationParameter(key = "cucumber.glue", value = "com.ga.investmentservice.cucumber")
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty,html:target/cucumber/asset-api.html,json:target/cucumber/asset-api.json,junit:target/cucumber/asset-api.xml")
@ConfigurationParameter(key = "cucumber.junit-platform.naming-strategy", value = "long")
public class CucumberTest {
}
