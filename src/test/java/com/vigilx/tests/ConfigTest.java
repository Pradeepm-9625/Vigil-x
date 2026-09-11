package com.vigilx.tests;
import com.vigilx.config.ConfigReader;

public class ConfigTest {

    public static void main(String[] args) {

        System.out.println(ConfigReader.get("base.url"));

        System.out.println(ConfigReader.get("browser"));

        System.out.println(ConfigReader.get("username"));

    }

}
