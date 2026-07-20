package com.example.test_g_locon.main;

import java.text.SimpleDateFormat;
import java.util.Date;

public class SetDate {
    public String convertLong(long ntpTime){
        Date date = new Date(ntpTime);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        String tmp = sdf.format(date);
        return tmp;
    }
}
