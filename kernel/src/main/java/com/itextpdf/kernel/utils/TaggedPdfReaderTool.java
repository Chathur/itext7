/*
    $Id$

    This file is part of the iText (R) project.
    Copyright (c) 1998-2016 iText Group NV
    Authors: Bruno Lowagie, Paulo Soares, et al.

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License version 3
    as published by the Free Software Foundation with the addition of the
    following permission added to Section 15 as permitted in Section 7(a):
    FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
    ITEXT GROUP. ITEXT GROUP DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
    OF THIRD PARTY RIGHTS

    This program is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
    or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License
    along with this program; if not, see http://www.gnu.org/licenses or write to
    the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
    Boston, MA, 02110-1301 USA, or download the license from the following URL:
    http://itextpdf.com/terms-of-use/

    The interactive user interfaces in modified source and object code versions
    of this program must display Appropriate Legal Notices, as required under
    Section 5 of the GNU Affero General Public License.

    In accordance with Section 7(b) of the GNU Affero General Public License,
    a covered work must retain the producer line in every PDF that is created
    or manipulated using iText.

    You can be released from the requirements of the license by purchasing
    a commercial license. Buying such a license is mandatory as soon as you
    develop commercial activities involving the iText software without
    disclosing the source code of your own applications.
    These activities include: offering paid services to customers as an ASP,
    serving PDFs on the fly in a web application, shipping iText with a closed
    source product.

    For more information, please contact iText Software Corp. at this
    address: sales@itextpdf.com
 */
package com.itextpdf.kernel.utils;

import com.itextpdf.kernel.PdfException;
import com.itextpdf.kernel.pdf.canvas.parser.data.EventData;
import com.itextpdf.kernel.pdf.canvas.parser.listener.EventListener;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.listener.TextExtractionStrategy;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfString;
import com.itextpdf.kernel.pdf.tagging.IPdfStructElem;
import com.itextpdf.kernel.pdf.tagging.PdfMcr;
import com.itextpdf.kernel.pdf.tagging.PdfObjRef;
import com.itextpdf.kernel.pdf.tagging.PdfStructElem;
import com.itextpdf.kernel.pdf.tagging.PdfStructTreeRoot;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a tagged PDF document into an XML file.
 */
 public class TaggedPdfReaderTool {

    protected PdfDocument document;
    protected PrintWriter out;
    protected String rootTag;

    // key - page dictionary; value pairs of mcid and text in them
    protected Map<PdfDictionary, Map<Integer, String> > parsedTags = new HashMap<>();

    public TaggedPdfReaderTool(PdfDocument document) {
        this.document = document;
    }

    public void convertToXml(OutputStream os)
            throws IOException {
        convertToXml(os, "UTF-8");
    }

    public void convertToXml(OutputStream os, String charset)
            throws IOException {
        OutputStreamWriter outs = new OutputStreamWriter(os, charset);
        out = new PrintWriter(outs);
        if (rootTag != null) {
            out.println("<" + rootTag + ">");
        }
        // get the StructTreeRoot from the document
        PdfStructTreeRoot structTreeRoot = document.getStructTreeRoot();
        if (structTreeRoot == null)
            throw new PdfException(PdfException.DocumentDoesntContainStructTreeRoot);
        // Inspect the child or children of the StructTreeRoot
        inspectKids(structTreeRoot.getKids());
        if (rootTag != null) {
            out.print("</" + rootTag + ">");
        }
        out.flush();
        out.close();
    }

    public TaggedPdfReaderTool setRootTag(String rootTagName) {
        this.rootTag = rootTagName;
        return this;
    }

    protected void inspectKids(List<IPdfStructElem> kids) {
        if (kids == null)
            return;

        for (IPdfStructElem kid : kids) {
            inspectKid(kid);
        }
    }

    protected void inspectKid(IPdfStructElem kid) {
        if (kid instanceof PdfStructElem) {
            PdfStructElem structElemKid = (PdfStructElem) kid;
            PdfName s = structElemKid.getRole();
            String tagN = s.getValue();
            String tag = fixTagName(tagN);
            out.print("<");
            out.print(tag);

            inspectAttributes(structElemKid);

            out.println(">");

            PdfString alt = (structElemKid).getAlt();

            if (alt != null) {
                out.print("<alt><![CDATA[");
                out.print(alt.getValue().replaceAll("[\\000]*", ""));
                out.println("]]></alt>");
            }

            inspectKids(structElemKid.getKids());
            out.print("</");
            out.print(tag);
            out.println(">");
        } else if (kid instanceof PdfMcr) {
            parseTag((PdfMcr) kid);
        } else {
            out.print(" <flushedKid/> ");
        }
    }

    protected void inspectAttributes(PdfStructElem kid) {
        PdfObject attrObj = kid.getAttributes(false);

        if (attrObj != null) {
            PdfDictionary attrDict;
            if (attrObj instanceof PdfArray) {
                attrDict = ((PdfArray) attrObj).getAsDictionary(0);
            } else {
                attrDict = (PdfDictionary) attrObj;
            }
            for (Map.Entry<PdfName, PdfObject> entry : attrDict.entrySet()) {
                out.print(' ');
                String attrName = entry.getKey().getValue();
                out.print(Character.toLowerCase(attrName.charAt(0)) + attrName.substring(1));
                out.print("=\"");
                out.print(entry.getValue().toString());
                out.print("\"");
            }
        }
    }

    protected void parseTag(PdfMcr kid) {
        int mcid = kid.getMcid();
        PdfDictionary pageDic = kid.getPageObject();

        String tagContent = "";
        if (mcid != -1) {
            if (!parsedTags.containsKey(pageDic)) {
                MarkedContentEventListener listener = new MarkedContentEventListener();

                PdfCanvasProcessor processor = new PdfCanvasProcessor(listener);
                PdfPage page = document.getCatalog().getPage(pageDic);
                processor.processContent(page.getContentBytes(), page.getResources());

                parsedTags.put(pageDic, listener.getMcidContent());
            }

            if (parsedTags.get(pageDic).containsKey(mcid))
                tagContent = parsedTags.get(pageDic).get(mcid);

        } else {
            PdfObjRef objRef = (PdfObjRef) kid;
            PdfObject object = objRef.getReferencedObject();
            if (object.isDictionary()) {
                PdfName subtype = ((PdfDictionary) object).getAsName(PdfName.Subtype);
                tagContent = subtype.toString();
            }
        }
        out.print(escapeXML(tagContent, true));
    }

    protected static String fixTagName(String tag) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tag.length(); ++k) {
            char c = tag.charAt(k);
            boolean nameStart =
                    c == ':'
                            || (c >= 'A' && c <= 'Z')
                            || c == '_'
                            || (c >= 'a' && c <= 'z')
                            || (c >= '\u00c0' && c <= '\u00d6')
                            || (c >= '\u00d8' && c <= '\u00f6')
                            || (c >= '\u00f8' && c <= '\u02ff')
                            || (c >= '\u0370' && c <= '\u037d')
                            || (c >= '\u037f' && c <= '\u1fff')
                            || (c >= '\u200c' && c <= '\u200d')
                            || (c >= '\u2070' && c <= '\u218f')
                            || (c >= '\u2c00' && c <= '\u2fef')
                            || (c >= '\u3001' && c <= '\ud7ff')
                            || (c >= '\uf900' && c <= '\ufdcf')
                            || (c >= '\ufdf0' && c <= '\ufffd');
            boolean nameMiddle =
                    c == '-'
                            || c == '.'
                            || (c >= '0' && c <= '9')
                            || c == '\u00b7'
                            || (c >= '\u0300' && c <= '\u036f')
                            || (c >= '\u203f' && c <= '\u2040')
                            || nameStart;
            if (k == 0) {
                if (!nameStart)
                    c = '_';
            }
            else {
                if (!nameMiddle)
                    c = '-';
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * NOTE: copied from itext5 XMLUtils class
     *
     * Escapes a string with the appropriated XML codes.
     * @param s the string to be escaped
     * @param onlyASCII codes above 127 will always be escaped with &amp;#nn; if <CODE>true</CODE>
     * @return the escaped string
     * @since 5.0.6
     */
    protected static String escapeXML(final String s, final boolean onlyASCII) {
        char cc[] = s.toCharArray();
        int len = cc.length;
        StringBuffer sb = new StringBuffer();
        for (int k = 0; k < len; ++k) {
            int c = cc[k];
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    if (isValidCharacterValue(c)) {
                        if (onlyASCII && c > 127)
                            sb.append("&#").append(c).append(';');
                        else
                            sb.append((char)c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Checks if a character value should be escaped/unescaped.
     * @param	c	a character value
     * @return	true if it's OK to escape or unescape this value
     */
    public static boolean isValidCharacterValue(int c) {
        return (c == 0x9 || c == 0xA || c == 0xD
                || c >= 0x20 && c <= 0xD7FF
                || c >= 0xE000 && c <= 0xFFFD
                || c >= 0x10000 && c <= 0x10FFFF);
    }

    private class MarkedContentEventListener implements EventListener {
        private Map<Integer, TextExtractionStrategy> contentByMcid = new HashMap<>();

        public Map<Integer, String> getMcidContent() {
            Map<Integer, String> content = new HashMap<>();
            for (int id : contentByMcid.keySet()) {
                content.put(id, contentByMcid.get(id).getResultantText());
            }
            return content;
        }

        @Override
        public void eventOccurred(EventData data, EventType type) {
            switch (type) {
                case RENDER_TEXT:
                    TextRenderInfo textInfo = (TextRenderInfo) data;
                    int mcid = textInfo.getMcid();
                    if (mcid != -1) {
                        TextExtractionStrategy textExtractionStrategy = contentByMcid.get(mcid);
                        if (textExtractionStrategy == null) {
                            textExtractionStrategy = new LocationTextExtractionStrategy();
                            contentByMcid.put(mcid, textExtractionStrategy);
                        }
                        textExtractionStrategy.eventOccurred(data, type);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return null;
        }
    }
}
