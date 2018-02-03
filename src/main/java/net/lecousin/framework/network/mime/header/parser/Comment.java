package net.lecousin.framework.network.mime.header.parser;

import java.util.List;

public class Comment implements Token {

	public Comment(List<Token> tokens) {
		this.tokens = tokens;
	}
	
	private List<Token> tokens;
	
	public List<Token> getContent() {
		return tokens;
	}
	
	@Override
	public String asText() {
		return "(" + Token.asText(tokens) + ")";
	}
	
}
