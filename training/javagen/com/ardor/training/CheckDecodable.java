package com.ardor.training;

import com.ardor.ir.AsciiActionCodec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Stdin-to-stdout filter used by eval.py to check whether fine-tuned-model
 * output is valid AsciiActionCodec syntax: one candidate command per input
 * line, one "OK" or "ERR: <message>" per output line. Kept separate from
 * GenerateDataset since eval checks arbitrary (possibly malformed) model
 * output rather than generating known-good samples.
 */
public final class CheckDecodable {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                AsciiActionCodec.decode(line);
                System.out.println("OK");
            } catch (Exception e) {
                System.out.println("ERR: " + e.getMessage());
            }
        }
    }
}
