package dev.nklip.javacraft.mathparser.parser;

/**
 * @author Nikita Lipatov
 **/
class Number {
    private double value;

    public Number() {
        this.value = 0.0;
    }

    public Number(double var) {
        this.value = var;
    }

    public void set(double var) {
        this.value = var;
    }

    public double get() {
        return value;
    }

    public void invertValue() {
        this.value = -value;
    }
}
