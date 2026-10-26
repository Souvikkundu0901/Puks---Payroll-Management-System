package com.octopyder.payroll;

import org.springframework.boot.SpringApplication;

public class TestPayrollApplication {

	public static void main(String[] args) {
		SpringApplication.from(PayrollApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
