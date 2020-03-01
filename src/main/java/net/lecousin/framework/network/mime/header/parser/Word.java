package net.lecousin.framework.network.mime.header.parser;

import net.lecousin.framework.text.IString;

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
	public int textLength() {
		return word.length();
	}
	
	@Override
	public void asText(IString s) {
		s.append(word);
	}
	
}
