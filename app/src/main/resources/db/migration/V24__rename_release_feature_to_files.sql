UPDATE instance_features
   SET feature_slug = 'files'
 WHERE feature_slug = 'releases'
   AND NOT EXISTS (
       SELECT 1
         FROM instance_features existing
        WHERE existing.feature_slug = 'files'
   );

DELETE FROM instance_features
 WHERE feature_slug = 'releases';
