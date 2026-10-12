# Subject outlines: where they come from, and how good they have to be

The subject shape ([compose-module.md](compose-module.md) §2) is normally a country or region
border, picked from the library that ships with the application. Once it is a visible edge on a
five-meter print, **the outline's resolution is a print-quality property**, as much as the zoom: a
coarse outline draws a coarse polygon however fine the tiles behind it are.

---

## 1. The arithmetic

On a print `P` millimeters wide covering `G` kilometers of ground, one millimeter of print is
`G / P` kilometers. On a 5 m wall across 500 km, **1 mm of print is 100 m of ground**: vertices 1 km
apart draw 10 mm straight segments, 40 km apart draw 400 mm ones – a smooth border against a visibly
faceted polygon.

The target is **vertex spacing under half a millimeter of print**, below what anybody can see and
below most printers' dot. On the 5 m/500 km wall that is **50 m on the ground**; on a 5 m print of a
50 km region it is 5 m, finer than any global dataset – only OpenStreetMap-derived data gets near.

## 2. The coarseness check

`Shapes.coarseness` measures every edge of the outline in millimeters of print, ring by ring (the
step from one ring to the next is no edge), in the print's projection and scale:

```
edgeMm = edge length in the Web Mercator unit square × PrintRect.mmPerUnit()
```

It reports the **median** and the maximum, with the number of edges – the median, because one long
segment across a lake is harmless and a uniformly coarse outline is the problem.

The studio asks when a subject arrives in step 1, from the library or a file, against the print as
it stands. Over a 2 mm median the step's hint says so: *"This outline is coarse for a print this
size: its edges are about 2.5 mm long on paper, over 34 segments. It will look visibly angular."* –
in the chosen units. Below that it says nothing. It is not asked again when the size changes, and
names no fix; the three there are: a finer outline, a smaller print, or the boundary stroke, which
hides facets.

## 3. Your own outline

GeoJSON, KML and CSV import stays, unrestricted, for what the library lacks or lacks finely enough
– a municipality, a parcel, a hand-drawn region, a national mapping agency's file. The check of §2
treats a bundled and an imported outline alike.

## 4. The bundled library

A person picks a name from a list and the outline is there; nobody has to find a dataset or compare
licenses.

### 4.1 The source

**Natural Earth 1:10m** – admin-0, admin-1 and map subunits, public domain, vertices about 1 km
apart – and nothing else.

| Source | License | Why not |
| --- | --- | --- |
| geoBoundaries ADM0–ADM2 | CC BY 4.0 | The candidate where Natural Earth is too coarse or lacks an entry; asks for attribution. |
| OpenStreetMap admin relations | ODbL | Share-alike on distribution: bundling it is a decision, not a default. |
| Eurostat NUTS | EuroGeographics terms | Redistribution in an installer not checked. |
| GADM | Not free for commercial use | The license. |

The index names the source once (`"source": "Natural Earth 1:10m (public domain)"`), and the About
window's Map data tab credits it with a link (`OutlineLibrary.source()`), although public domain
asks for none. An export's `<project>-attribution.txt` names the tile provider only.

### 4.2 Every boundary, and the user chooses

The application takes no position on what a country is: admin-0, subunits and admin-1 all ship, and
nothing in the source is omitted, disputed boundaries included. `OutlineLibraryTest` fails if Taiwan,
Palestine, Kosovo, Western Sahara, Tibet, Scotland, Wales, Crimea or California stops being offered.

### 4.3 Format and loading

GeoJSON resources of `wtf-studio` under `net/trdlo/wtf/studio/outlines`, Deflate-compressed as jar
entries. `index.json` holds each entry's id, names, ISO code, parent, kind, level, bounding box,
vertex count and file, and is read once, on first use, for the searchable list (`OutlineLibrary`);
geometry is read one file at a time when an entry is chosen, the last three kept. Names are in the
interface language: a continent's from the bundle, a country's from the JDK, a region's local name
in the interface language's own country; everything else in English (D15).

### 4.4 Point-of-view variants

Natural Earth's default is *de facto*, and it publishes *de jure* variants for about thirty states.
The importer takes the default only; the labeled alternatives are open in the backlog.

### 4.5 The import

`tools/outlines/OutlineImport.java` turns the published GeoJSON into the library. It is run by hand
when the data is refreshed, never by the build:

```bash
cd <scratch>
curl -sL -o admin0.geojson   https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson
curl -sL -o admin1.geojson   https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
curl -sL -o subunits.geojson https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_map_subunits.geojson

cd <the repository>
mvn -q -pl wtf-compose dependency:build-classpath -Dmdep.outputFile=cp.txt
java -Xmx3g -cp "$(cat wtf-compose/cp.txt)" tools/outlines/OutlineImport.java <scratch>
```

It rewrites `wtf-studio/src/main/resources/net/trdlo/wtf/studio/outlines` in place: `index.json`, a
continent per file in `c/`, a country or subunit per file in `a0/`, and each country's subdivisions
in one file in `a1/`. It keeps a dozen attributes and rounds coordinates to five decimals (a meter),
with no date, so unchanged data gives the same bytes (D35).

**Subunits** are imported for the countries that have more than one, because Natural Earth records
Scotland only as a property of its council areas: Scotland, Wales, England and Corsica are entries
because of them. Continents are unioned from the subunits, which put French Guiana in South America
and Asian Russia in Asia.

**A gap of the source**: Spain is subdivided by province, so Girona is an entry and Catalonia is
not – the kind of case geoBoundaries would close.
