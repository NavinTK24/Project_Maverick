# Map-aided dead reckoning (docs/edge/edge_tracks.pkl)

| method | T (s) | n | drift median % | p90 % | % under 10 % |
|---|---:|---:|---:|---:|---:|
| DR + map (particle filter) | 30 | 291 | 7.54 | 28.58 | 60.1 |
| DR + map (particle filter) | 60 | 290 | 6.01 | 28.40 | 65.2 |
| DR + map (particle filter) | 120 | 286 | 5.38 | 41.95 | 61.2 |
| DR, no map | 30 | 291 | 11.14 | 31.71 | 45.7 |
| DR, no map | 60 | 290 | 11.48 | 29.84 | 43.8 |
| DR, no map | 120 | 286 | 12.28 | 24.97 | 40.9 |
| MaverickGRID: DR + map + consistency | 30 | 291 | 7.54 | 28.58 | 60.1 |
| MaverickGRID: DR + map + consistency | 60 | 290 | 6.01 | 28.40 | 64.8 |
| MaverickGRID: DR + map + consistency | 120 | 286 | 5.16 | 34.43 | 61.2 |

Paired vs DR, no map (same outages): share better, median difference, 95 % bootstrap CI

- DR + map (particle filter), T=30: better in 65 %, median diff -2.11 pts, CI [-2.97, -1.11]
- DR + map (particle filter), T=60: better in 67 %, median diff -4.06 pts, CI [-5.32, -3.20]
- DR + map (particle filter), T=120: better in 66 %, median diff -4.27 pts, CI [-5.22, -2.53]
- MaverickGRID: DR + map + consistency, T=30: better in 65 %, median diff -2.11 pts, CI [-3.07, -1.11]
- MaverickGRID: DR + map + consistency, T=60: better in 67 %, median diff -4.06 pts, CI [-5.39, -3.20]
- MaverickGRID: DR + map + consistency, T=120: better in 68 %, median diff -4.27 pts, CI [-5.26, -2.51]

Per held-out drive (median drift %, all T):

- S1: DR + map (particle filter) 6.93, DR, no map 13.27, MaverickGRID 6.93
- S2: DR + map (particle filter) 7.37, DR, no map 12.56, MaverickGRID 7.42
- S3a: DR + map (particle filter) 4.86, DR, no map 8.32, MaverickGRID 4.86
- S3c: DR + map (particle filter) 5.61, DR, no map 10.00, MaverickGRID 5.61