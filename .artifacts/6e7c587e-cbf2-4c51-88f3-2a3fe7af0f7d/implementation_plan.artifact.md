# Corrección de la función getNewTotalChapters

La función `getNewTotalChapters` en `BookDownloader2.kt` tiene varios errores de lógica en su manejo de retornos y bloques `finally`, lo que provoca que siempre devuelva `null` y no informe correctamente sobre las actualizaciones de capítulos o migraciones de IDs.

## Problemas Identificados

1.  **Retorno forzado en `finally`**: El bloque `finally` contiene un `return@withPermit null` (o `return@withPermit`), lo que sobreescribe cualquier valor calculado en el bloque `try` y provoca que la función siempre devuelva `null`.
2.  **Valor de éxito no retornado**: Cuando se detectan nuevos capítulos, el objeto `ResultCached` actualizado no se devuelve al llamador.
3.  **Manejo de migración**: Si el ID de la novela cambia (migración), el resultado devuelto debe reflejar el nuevo ID.

## Propuestos Cambios

### [BookDownloader2.kt](file:///C:/Users/Usuario/Downloads/Programacion/QuickNovelBranchs/spanishAndImprovements/QuickNovel/app/src/main/java/com/lagradost/quicknovel/BookDownloader2.kt)

#### [MODIFY] [getNewTotalChapters](file:///C:/Users/Usuario/Downloads/Programacion/QuickNovelBranchs/spanishAndImprovements/QuickNovel/app/src/main/java/com/lagradost/quicknovel/BookDownloader2.kt#L1321-L1359)
- Eliminar el `return` del bloque `finally`.
- Capturar y devolver el objeto `ResultCached` actualizado al final del bloque `try`.
- Asegurar que el retorno sea `null` en caso de error o si no hay cambios.
- Usar el objeto actualizado en `setKey`.

## Plan de Verificación

### Manual Verification
1.  **Actualización de Librería**: Forzar una actualización de la librería y verificar que los contadores de capítulos se actualizan visualmente.
2.  **Logs**: Verificar que no hay errores de compilación relacionados con tipos de retorno en lambdas.
