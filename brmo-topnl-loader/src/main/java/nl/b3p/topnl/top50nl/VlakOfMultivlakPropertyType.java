//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.8-b130911.1802 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2018.12.31 at 10:32:54 AM CET 
//


package nl.b3p.topnl.top50nl;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for VlakOfMultivlakPropertyType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="VlakOfMultivlakPropertyType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element ref="{http://register.geostandaarden.nl/gmlapplicatieschema/top50nl/1.1.1}VlakOfMultivlak"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "VlakOfMultivlakPropertyType", namespace = "http://register.geostandaarden.nl/gmlapplicatieschema/top50nl/1.1.1", propOrder = {
    "vlakOfMultivlak"
})
public class VlakOfMultivlakPropertyType {

    @XmlElement(name = "VlakOfMultivlak", required = true)
    protected VlakOfMultivlakType vlakOfMultivlak;

    /**
     * Gets the value of the vlakOfMultivlak property.
     * 
     * @return
     *     possible object is
     *     {@link VlakOfMultivlakType }
     *     
     */
    public VlakOfMultivlakType getVlakOfMultivlak() {
        return vlakOfMultivlak;
    }

    /**
     * Sets the value of the vlakOfMultivlak property.
     * 
     * @param value
     *     allowed object is
     *     {@link VlakOfMultivlakType }
     *     
     */
    public void setVlakOfMultivlak(VlakOfMultivlakType value) {
        this.vlakOfMultivlak = value;
    }

}