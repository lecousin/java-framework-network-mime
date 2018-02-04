package net.lecousin.framework.network.mime.header.parser;

/** Word (text) token. */
public class Word implements Token {

	/** Constructor. */
	public Word(String str) {
		this.word = str;
	}
	
	private String word;
	
	public String getContent() {
		return word;
	}
	
	@Override
	public String asText() {
		return word;
	}
	
}
