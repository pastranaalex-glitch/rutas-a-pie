package com.alex.rutasapie
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
class RouteCodecTest {
    private val sample="""{"id":1,"nombre":"Café ☕","provincia":"Madrid","comoLlegar":"Inicio","consejos":["Agua"]}"""
    @Test fun unicodeRoundTrip() { val r=RouteCodec.decode(sample,true); assertEquals(r,RouteCodec.decode(RouteCodec.encode(r),true)) }
    @Test fun supportsOldField() = assertEquals("Inicio",RouteCodec.decode(sample.replace("comoLlegar","como_llegar"),true).single().comoLlegar)
    @Test fun supportsWrapper() = assertEquals(1,RouteCodec.decode("{\"rutas\":[$sample]}",true).size)
    @Test fun supportsBom() = assertEquals(1,RouteCodec.decode("\uFEFF[$sample]",true).size)
    @Test(expected=IllegalArgumentException::class) fun blankNameRejected() {RouteCodec.decode("{\"nombre\":\" \"}",true)}
    @Test(expected=Exception::class) fun invalidMemberRejectsWholeFile() {RouteCodec.decode("[$sample,5]",true)}
    @Test(expected=IllegalArgumentException::class) fun wrongTypeRejected() {RouteCodec.decode("{\"nombre\":55}",true)}
    @Test(expected=IllegalArgumentException::class) fun negativeIdRejected() {RouteCodec.decode(sample.replace("\"id\":1","\"id\":-3"),true)}
    @Test(expected=IllegalArgumentException::class) fun excessiveBytesRejected() {RouteCodec.read(ByteArrayInputStream(ByteArray(RouteCodec.MAX_BYTES+1)))}
    @Test(expected=IllegalArgumentException::class) fun longNameRejected() {RouteCodec.decode("{\"nombre\":\"${"x".repeat(201)}\"}",true)}
    @Test(expected=IllegalArgumentException::class) fun futureVersionRejected() {RouteCodec.decode("{\"version\":2,\"rutas\":[]}",true)}
    @Test fun exportingThenImportingDoesNotDuplicate() {
        val base=RouteCodec.decode(sample,false)
        assertTrue(RouteCodec.uniqueIncoming(base,RouteCodec.decode(RouteCodec.encode(base),true)).isEmpty())
    }
    @Test fun changedVariantPreserved() {
        val r=RouteCodec.decode(sample,true);assertEquals(1,RouteCodec.uniqueIncoming(r,listOf(r[0].copy(descripcion="Otra variante"))).size)
    }
    @Test fun incomingIdNeverOverwritesExisting() {
        val r=RouteCodec.decode(sample,true);val n=RouteCodec.assignIds(r,r,6001)
        assertEquals(6001,n.single().id);assertEquals(1,r.single().id)
    }
    @Test fun duplicatesInsideImportSkipped() {
        val r=RouteCodec.decode(sample,true);assertEquals(1,RouteCodec.uniqueIncoming(emptyList(),r+r).size)
    }
    @Test(expected=IllegalArgumentException::class) fun tooManyRoutesRejected() {RouteCodec.decode("["+(1..1001).joinToString(","){sample}+"]",true)}
    @Test(expected=Exception::class) fun invalidUtf8Rejected() {RouteCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(),0x28)))}
    @Test(expected=IllegalArgumentException::class) fun fractionalVersionRejected() {RouteCodec.decode("{\"version\":1.5,\"rutas\":[]}",true)}
}
