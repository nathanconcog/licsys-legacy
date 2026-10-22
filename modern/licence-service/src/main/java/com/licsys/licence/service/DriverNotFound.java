package com.licsys.licence.service;

public class DriverNotFound extends RuntimeException {

    public DriverNotFound(String driverId) {
        super("Driver " + driverId + " is not registered");
    }
}
