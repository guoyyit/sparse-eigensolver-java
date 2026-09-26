package com.github.guoyyit.sparseeigensolverjava.api.dto;

/**
 * Wire format for a symmetric CSR matrix (upper triangle stored).
 */
public class CsrMatrixDto {
    public int n;
    public int[] rowPtr;
    public int[] colIndices;
    public double[] values;
}
