package com.hkg.crawler.parser;

import com.hkg.crawler.common.CanonicalUrl;

/**
 * Parse a fetched HTML document into a structured {@link ParsedDocument}.
 *
 * <p>Production implementation: {@link JsoupHtmlParser}.
 *
 * <p>Inputs come from the Fetcher (raw HTML) or the Render Service
 * (rendered HTML). The output is identical in shape; downstream
 * consumers can't tell which path produced it.
 */
public interface HtmlParser {

    /**
     * Parse the given HTML body. The {@code sourceUrl} is the
     * canonicalized URL we fetched (after redirects), used to resolve
     * relative links and as the {@code base} for {@code <base href>}.
     */
    ParsedDocument parse(CanonicalUrl sourceUrl, String html);
}
