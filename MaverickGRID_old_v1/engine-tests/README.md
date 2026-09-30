# Engine tests (desktop Java 11+, no Android needed)

First export models and test data from the Python package (in the Maverick repo):

    python -m idr.export_java --root "<...>\Categorised IOVNB Dataset" --out export --parity S1     (repeat for S2, S3a, S3c)
    python -m idr.osm_pack --in data/osm/coventry_roads.json --out export/maps/coventry.mgr

Then compile and run (from this folder, Windows PowerShell shown):

    javac -d out ..\app\src\main\java\com\maverickgrid\engine\*.java com\maverickgrid\engine\*.java
    java -cp out com.maverickgrid.engine.ParityTest phone <path>\export S1      # Java vs Python: features, stop detector, speed, heading
    java -cp out com.maverickgrid.engine.ParityTest edge  <path>\export S1
    java -cp out com.maverickgrid.engine.Replay phone <path>\export <path>\export\maps\coventry.mgr S1,S2,S3a,S3c 2
    java -cp out com.maverickgrid.engine.Replay edge  <path>\export <path>\export\maps\coventry.mgr S1,S2,S3a,S3c 2
    java -cp out com.maverickgrid.engine.AppReplaySim ..\app\src\main\assets

Expected: ParityTest speed/heading differences 0; Replay median drift phone 9.9 / 6.2 / 5.0 %, edge 7.5 / 5.7 / 4.2 % (30 / 60 / 120 s).
