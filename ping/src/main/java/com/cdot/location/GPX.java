/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.location;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class GPX {
    private static final String TAG = GPX.class.getSimpleName();

    // GPX
    public static final SimpleDateFormat ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    // Namespaces
    public static final String NS_GPX = "http://www.topografix.com/GPX/1/1";
    //xmlns:xsi=http://www.w3.org/2001/XMLSchema-instance
    //xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd

    public static Document openDocument(ContentResolver cr, Uri uri, String creator) {
        Document gpxDocument = null;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setValidating(false);
        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            Element gpxTrk;
            try {
                AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
                InputSource source = new InputSource(new FileInputStream(afd.getFileDescriptor()));
                Log.d(TAG, "Parsing existing content");
                gpxDocument = db.parse(source);
                afd.close();
                Log.d(TAG, "...existing content retained");
            } catch (Exception ouch) {
                Log.e(TAG, ouch.toString());
                Log.d(TAG, "Creating new document");
                gpxDocument = db.newDocument();
                gpxDocument.setDocumentURI(uri.toString());
                gpxDocument.setXmlVersion("1.1");
                gpxDocument.setXmlStandalone(true);
                Element gpxGpx = gpxDocument.createElementNS(GPX.NS_GPX, "gpx");
                gpxGpx.setAttribute("version", "1.1");
                gpxGpx.setAttribute("creator", creator);
                gpxDocument.appendChild(gpxGpx);
                gpxTrk = gpxDocument.createElementNS(GPX.NS_GPX, "trk");
                gpxGpx.appendChild(gpxTrk);
            }
        } catch (ParserConfigurationException ignore) {
        }
        return gpxDocument;
    }
}
