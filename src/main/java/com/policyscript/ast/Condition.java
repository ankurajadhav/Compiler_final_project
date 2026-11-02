package com.policyscript.ast;

import java.time.DayOfWeek;
import java.time.LocalTime;

public interface Condition {
    boolean evaluate(DayOfWeek day, LocalTime time);
    String describe();
    String canonical();
    int line();
    int column();
}
