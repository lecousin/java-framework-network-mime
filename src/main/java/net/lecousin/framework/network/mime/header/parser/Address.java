package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

/** Address token. */
public class Address implements Token {

	/** Constructor. */
	public Address(List<Token> tokens) {
		this.tokens = tokens;
	}
	
	private List<Token> tokens;
	
	public List<Token> getContent() {
		return tokens;
	}
	
	@Override
	public String asText() {
		return "<" + Token.asText(tokens) + ">";
	}
	
}
