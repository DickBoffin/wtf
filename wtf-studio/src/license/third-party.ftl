<#--
  THIRD-PARTY.txt, the notices every installation carries beside the application's jars.

  license-maven-plugin (add-third-party, in wtf-studio's pom) fills in the libraries from the poms
  of what wtf-studio ships; what is not a Maven dependency is written here by hand. A license a
  line names in parentheses has its full text in licenses/<name>.txt, which src/license/licenses
  holds and the build copies beside this file. ThirdPartyNoticesIT checks both: every jar in
  target/libs has its line, and every license named has its text.

  Nothing here comes from the clock or the machine. WTF's own modules are versioned together, so
  the studio's version is the one its sibling modules have.
-->
<#function licenseFormat licenses>
    <#assign result = ""/>
    <#list licenses as license>
        <#assign result = result + "(" + license + ") "/>
    </#list>
    <#return result>
</#function>
<#function artifactFormat p>
    <#return p.name + " (" + p.groupId + ":" + p.artifactId + ":" + p.version + " - " + (p.url!"no url") + ")">
</#function>
<#assign version = "">
<#list dependencyMap as e>
    <#if e.getKey().groupId == "net.trdlo.wtf">
        <#assign version = e.getKey().version>
    </#if>
</#list>
WTF – the Web Tile Framework – and the work of others it carries

WTF is free software under the GNU General Public License, version 3 or later. It is built on the
software and data below, each passed on under the license named in parentheses at the start of
its line. The full text of each license is in the licenses folder beside this file, under that
name: licenses/Apache-2.0.txt. Where a line names two licenses, its authors offer a choice of
either – except for Font Awesome, which licenses its icons and its fonts apart, both of which
apply.

A library's own notices – a NOTICE file, a copyright line – are inside its jar, in META-INF, and
travel with it.


WTF itself

    (GPL-3.0-or-later) WTF :: studio (net.trdlo.wtf:wtf-studio:${version} - https://github.com/DickBoffin/wtf)


The libraries, ${dependencyMap?size}, from their Maven poms

<#list dependencyMap as e>
    ${licenseFormat(e.getValue())}${artifactFormat(e.getKey())}
</#list>

The JavaFX modules (org.openjfx) are not kept as jars in an installation: they are linked into
the Java runtime the application runs on. That runtime is OpenJDK, and the notices of its own
modules are in its legal folder.

    (GPL-2.0-with-classpath-exception) OpenJDK, the Java runtime (https://openjdk.org/)


Inside a library, or data WTF bundles

    (CC-BY-4.0) (OFL-1.1) Font Awesome Free 5.15.3 – the icons, as the fonts inside ikonli-fontawesome5-pack; Copyright (c) Fonticons, Inc. (https://fontawesome.com/license/free)
    (OFL-1.1) Liberation Sans – the font of the printed credit, inside wtf-compose; Copyright (c) 2010 Google Corporation, with Reserved Font Arimo, Tinos and Cousine; Copyright (c) 2012 Red Hat, Inc., with Reserved Font Name Liberation (https://github.com/liberationfonts/liberation-fonts)
    (Public-Domain) Natural Earth 1:10m – the country and region outlines, inside wtf-studio (https://www.naturalearthdata.com/)


A mark, not a license

The Mapy.com logo inside wtf-tiles (net/trdlo/wtf/tiles/logos/mapy-com.svg) is Mapy.com's mark.
It is not WTF's to license: WTF carries it only to print it beside Mapy.com's credit, as
Mapy.com's attribution rules ask (https://developer.mapy.com/rest-api-mapy-cz/atribution/).
