# Map-aided dead reckoning (docs/idr/phone_tracks.pkl)

| method | T (s) | n | drift median % | p90 % | % under 10 % |
|---|---:|---:|---:|---:|---:|
| DR + map (particle filter) | 30 | 291 | 10.92 | 34.66 | 47.1 |
| DR + map (particle filter) | 60 | 289 | 7.34 | 35.23 | 58.5 |
| DR + map (particle filter) | 120 | 285 | 7.73 | 46.01 | 55.1 |
| DR, no map | 30 | 291 | 17.36 | 37.19 | 27.5 |
| DR, no map | 60 | 289 | 17.05 | 34.48 | 23.2 |
| DR, no map | 120 | 285 | 18.65 | 38.00 | 23.5 |
| MaverickGRID: DR + map + consistency | 30 | 291 | 10.97 | 34.66 | 46.7 |
| MaverickGRID: DR + map + consistency | 60 | 289 | 7.36 | 33.15 | 58.1 |
| MaverickGRID: DR + map + consistency | 120 | 285 | 8.74 | 37.22 | 53.3 |

Paired vs DR, no map (same outages): share better, median difference, 95 % bootstrap CI

- DR + map (particle filter), T=30: better in 67 %, median diff -3.57 pts, CI [-4.63, -2.55]
- DR + map (particle filter), T=60: better in 67 %, median diff -5.26 pts, CI [-7.27, -3.64]
- DR + map (particle filter), T=120: better in 71 %, median diff -5.42 pts, CI [-7.31, -4.12]
- MaverickGRID: DR + map + consistency, T=30: better in 67 %, median diff -3.57 pts, CI [-4.68, -2.68]
- MaverickGRID: DR + map + consistency, T=60: better in 69 %, median diff -5.71 pts, CI [-7.43, -3.67]
- MaverickGRID: DR + map + consistency, T=120: better in 74 %, median diff -5.62 pts, CI [-7.43, -4.45]

Per held-out drive (median drift %, all T):

- S1: DR + map (particle filter) 8.47, DR, no map 18.65, MaverickGRID 8.70
- S2: DR + map (particle filter) 8.90, DR, no map 16.69, MaverickGRID 9.22
- S3a: DR + map (particle filter) 5.21, DR, no map 9.76, MaverickGRID 5.21
- S3c: DR + map (particle filter) 14.79, DR, no map 23.10, MaverickGRID 15.71