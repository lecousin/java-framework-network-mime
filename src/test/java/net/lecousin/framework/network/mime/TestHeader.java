package net.lecousin.framework.network.mime;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.MimeHeadersContainer;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;
import net.lecousin.framework.network.mime.header.parser.Address;
import net.lecousin.framework.network.mime.header.parser.Comment;
import net.lecousin.framework.network.mime.header.parser.DomainLiteral;
import net.lecousin.framework.network.mime.header.parser.MimeHeaderValueParser;
import net.lecousin.framework.network.mime.header.parser.Space;
import net.lecousin.framework.network.mime.header.parser.Token;
import net.lecousin.framework.network.mime.header.parser.Word;
import net.lecousin.framework.text.CharArrayStringBuffer;

import org.junit.Assert;
import org.junit.Test;

public class TestHeader extends LCCoreAbstractTest {

	@Test
	public void testMimeHeader() throws Exception {
		MimeHeader h = new MimeHeader("X-Test", "toto; titi=tata; (a comment) hello=world, heho, aa; bb=cc");
		Assert.assertEquals("X-Test", h.getName());
		Assert.assertEquals("x-test", h.getNameLowerCase());
		Assert.assertEquals("toto; titi=tata; (a comment) hello=world, heho, aa; bb=cc", h.getRawValue());
		ParameterizedHeaderValues values = h.getValue(ParameterizedHeaderValues.class);
		Assert.assertEquals(3, values.getValues().size());
		ParameterizedHeaderValue v = values.getMainValue("toto");
		Assert.assertNotNull(v);
		Assert.assertEquals(2, v.getParameters().size());
		Assert.assertEquals("tata", v.getParameter("titi"));
		Assert.assertEquals("world", v.getParameter("hello"));
		Assert.assertNull(v.getParameter("heho"));
		Assert.assertNull(v.getParameter("aa"));
		Assert.assertNull(v.getParameter("bb"));
		v = values.getMainValue("heho");
		Assert.assertNotNull(v);
		Assert.assertEquals(0, v.getParameters().size());
		Assert.assertNull(v.getParameter("aa"));
		Assert.assertNull(v.getParameterIgnoreCase("bb"));
		v = values.getMainValue("aa");
		Assert.assertNotNull(v);
		Assert.assertEquals(1, v.getParameters().size());
		Assert.assertEquals("cc", v.getParameter("bb"));
		
		Assert.assertNull(values.getMainValue("abcd"));
		Assert.assertFalse(values.hasMainValue("abcd"));
		Assert.assertTrue(values.hasMainValue("aa"));
		CharArrayStringBuffer s = new CharArrayStringBuffer();
		values.generate(s, 17, 13);
		Assert.assertEquals("toto;titi=tata;\r\n\thello=world,\r\n\theho,aa;bb=cc", s.toString());
		
		h.setRawValue("hello; fr=bonjour");
		Assert.assertEquals("hello; fr=bonjour", h.getRawValue());
		h.appendTo(new CharArrayStringBuffer());
		v = h.getValue(ParameterizedHeaderValue.class);
		Assert.assertEquals("hello", v.getMainValue());
		Assert.assertEquals("bonjour", v.getParameter("fr"));
		v = h.getValue(ParameterizedHeaderValue.class);
		Assert.assertEquals("hello", v.getMainValue());
		Assert.assertEquals("bonjour", v.getParameterIgnoreCase("FR"));
		h.setValue(new ParameterizedHeaderValue("world", "fr", "monde", "test", "yes"));
		Assert.assertEquals("world;fr=monde;test=yes", h.getRawValue());
		h.setValue(new ParameterizedHeaderValue("world", "fr", "monde", "test", "yes"));
		h.appendTo(new CharArrayStringBuffer());
		v.setMainValue("hello");
		v.setParameter("turlututu", "pointu");
		
		MimeHeaders mime = new MimeHeaders(
			new MimeHeader("h1", "v1"),
			new MimeHeader("h2", "v2")
		);
		mime.add(new MimeHeader("h3", "v3"));
		mime.set(new MimeHeader("h1", "v11"));
		mime.set(new MimeHeader("h4", "v4"));
		Assert.assertEquals(4, mime.getHeaders().size());
	}
	
	@Test
	public void testParser() {
		List<Token> tokens = MimeHeaderValueParser.parse("hello (a comment)  world [domain]  <user@mail.com> \"bonjour  \\\"ami\\\"\"");
		Iterator<Token> it = tokens.iterator();
		Token tok = it.next();
		Assert.assertEquals(Word.class, tok.getClass());
		Assert.assertEquals("hello", ((Word)tok).getContent());
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(Comment.class, tok.getClass());
		Assert.assertEquals("(a comment)", ((Comment)tok).asString());
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(Word.class, tok.getClass());
		Assert.assertEquals("world", ((Word)tok).getContent());
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(DomainLiteral.class, tok.getClass());
		Assert.assertEquals("[domain]", ((DomainLiteral)tok).asString());
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(Address.class, tok.getClass());
		Assert.assertEquals("<user@mail.com>", ((Address)tok).asString());
		((Address)tok).getContent();
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(Word.class, tok.getClass());
		Assert.assertEquals("bonjour  \"ami\"", ((Word)tok).getContent());
		Assert.assertFalse(it.hasNext());

	
		tokens = MimeHeaderValueParser.parse("(comment1 [domain1 (comment2) [domain2]] (comment3))");
		it = tokens.iterator();
		tok = it.next();
		Assert.assertEquals(Comment.class, tok.getClass());
		Assert.assertFalse(it.hasNext());
		it = ((Comment)tok).getContent().iterator();
		tok = it.next();
		Assert.assertEquals(Word.class, tok.getClass());
		Assert.assertEquals("comment1", ((Word)tok).getContent());
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(DomainLiteral.class, tok.getClass());

		Iterator<Token> it2 = ((DomainLiteral)tok).getContent().iterator();
		Token tok2 = it2.next();
		Assert.assertEquals(Word.class, tok2.getClass());
		Assert.assertEquals("domain1", ((Word)tok2).getContent());
		tok2 = it2.next();
		Assert.assertEquals(Space.class, tok2.getClass());
		tok2 = it2.next();
		Assert.assertEquals(Comment.class, tok2.getClass());
		Assert.assertEquals("(comment2)", ((Comment)tok2).asString());
		tok2 = it2.next();
		Assert.assertEquals(Space.class, tok2.getClass());
		tok2 = it2.next();
		Assert.assertEquals(DomainLiteral.class, tok2.getClass());
		Assert.assertEquals("[domain2]", ((DomainLiteral)tok2).asString());
		Assert.assertFalse(it2.hasNext());
		
		tok = it.next();
		Assert.assertEquals(Space.class, tok.getClass());
		tok = it.next();
		Assert.assertEquals(Comment.class, tok.getClass());
		Assert.assertEquals("(comment3)", ((Comment)tok).asString());
		Assert.assertFalse(it.hasNext());
		
		tokens = MimeHeaderValueParser.parse(" hel\\5lo ");
		Token.trim(tokens);
		Assert.assertEquals(1, tokens.size());
		Assert.assertEquals(Word.class, tokens.get(0).getClass());
		Assert.assertEquals("hel5lo", ((Word)tokens.get(0)).getContent());
	}
	
	@Test
	public void testMimeHeaders() {
		MimeHeaders headers = new MimeHeaders();
		headers.setRawValue("test", "hello");
		Assert.assertNull(headers.getFirstLongValue("test"));
		Assert.assertTrue(headers.has("test"));
		Assert.assertFalse(headers.has("test2"));
		Assert.assertNull(headers.getContentTypeValue());
		headers.setRawValue("Content-Type", "=?toto?=");
		Assert.assertNull(headers.getContentTypeValue());
		
		AsyncSupplier<Boolean, MimeException> consume = headers.createConsumer(10).consume(new ByteArray("X-Header: x-value\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
		
		consume = headers.createConsumer(10).consume(new ByteArray("X-Header-TooLong: x-value\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
		
		consume = headers.createConsumer(10).consume(new ByteArray("X: y\r\nZ: zzzzzzzzzz\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
		
		consume = headers.createConsumer(10).consume(new ByteArray("X: abcde\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
		
		consume = headers.createConsumer(100).consume(new ByteArray(" X: abcde\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
		
		consume = headers.createConsumer(100).consume(new ByteArray("123465789\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
		consume.block(0);
		Assert.assertTrue(consume.hasError());
	}
	
	public static class Container implements MimeHeadersContainer<Container> {
		
		private MimeHeaders headers = new MimeHeaders();
		
		@Override
		public MimeHeaders getHeaders() {
			return headers;
		}
	}
	
	@Test
	public void testHeadersContainer() throws Exception {
		Container c = new Container();
		c.addHeader(new MimeHeader("a", "b"));
		c.addHeader("c", "d");
		c.addHeader("e", new ParameterizedHeaderValue("f", "g", "h"));
		Assert.assertEquals("b", c.getHeaders().getFirstRawValue("a"));
		Assert.assertEquals("d", c.getHeaders().getFirstRawValue("c"));
		c.setHeader("c", "1");
		Assert.assertEquals("1", c.getHeaders().getFirstRawValue("c"));
		c.setHeader(new MimeHeader("c", "2"));
		Assert.assertEquals("2", c.getHeaders().getFirstRawValue("c"));
		c.setHeader("c", new ParameterizedHeaderValue("A", "B", "C"));
		Assert.assertEquals("A", c.getHeaders().getFirstValue("c", ParameterizedHeaderValue.class).getMainValue());
	}
	
}
