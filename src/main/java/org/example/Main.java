package org.example;


public class Main {
    public static void main(String[] args) {
        System.out.println("Hello Java SDK Example!");
        String configFile = "src/main/java/org/example/run_config.json";

        Example example = new Example(configFile);
        System.out.println("example: " + example);

        // INFO: 控制台输入回车 --> 程序退出
        example.run(0, false);
    }
}