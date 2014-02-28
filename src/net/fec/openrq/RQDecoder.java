package net.fec.openrq;


import net.fec.openrq.parameters.DataParameters;


/**
 * @author Jos&#233; Lopes &lt;jlopes&#064;lasige.di.fc.ul.pt&gt;
 * @author Ricardo Fonseca &lt;ricardof&#064;lasige.di.fc.ul.pt&gt;
 */
public interface RQDecoder {

    /**
     * Returns the transport parameters associated to this decoder.
     * 
     * @return the transport parameters associated to this decoder
     */
    public DataParameters getTransportParameterss();
}