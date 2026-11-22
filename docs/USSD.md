# Module USSD (fork)

Ajout à SMS Gateway for Android : composer un code USSD **complet en une seule fois** à la demande de
l'API locale, avec `TelephonyManager.sendUssdRequest` (Android 8 ou plus). Aucune boîte ne s'affiche,
aucun service d'accessibilité n'est nécessaire. Code : `modules/ussd/` et `localserver/routes/UssdRoutes.kt`.

Le téléphone ne décide rien : le serveur (Void Gate) fournit le code, vérifie la réponse de l'opérateur,
et seule la preuve de l'opérateur (SMS « Envoi de ») confirme un mouvement d'argent. Les codes vivent
côté serveur : si l'opérateur change son menu, on corrige le serveur, sans recompiler l'application.

## API

`POST /ussd` (authentification Basic de l'API locale) :

```json
{ "id": "trf-000123-x", "code": "*144*1*1*627023108*1000*1*1*{PIN}#", "simNumber": 1, "timeoutSeconds": 60 }
```

| Champ | Règle |
|---|---|
| `id` | Clé d'idempotence, 1 à 64 caractères `A-Za-z0-9_-`. Absent : généré. |
| `code` | Chiffres, `*` et `#`, terminé par `#`, plus `{PIN}` au plus une fois. |
| `simNumber` | 1, 2… Absent : SIM par défaut. |
| `timeoutSeconds` | 10 à 180, défaut 60. |

`{PIN}` est remplacé sur le téléphone par le code réglé dans Paramètres > USSD, au moment de composer.
Le PIN n'est ni reçu par l'API, ni stocké dans la session, ni journalisé.

Réponses : `202` session en file ; `400` requête invalide ; `409` id déjà composé mais état perdu
(redémarrage) : ne jamais renvoyer avec un autre id avant d'avoir vérifié le SMS.

`GET /ussd/{id}` : état (`Pending`, `Running`, `Completed`, `Failed`), `response` (texte de l'opérateur),
`error`, `failureCode`. `404` si inconnue depuis le dernier démarrage.

Webhooks : `ussd:completed` (réponse reçue) et `ussd:failed`, même contenu que `GET /ussd/{id}`.

## Règles

- Une session à la fois, les suivantes attendent en file.
- Un id est enregistré AVANT de composer : il n'est jamais recomposé, même après redémarrage.
- `Completed` veut seulement dire que l'opérateur a répondu : c'est au serveur de lire `response`.

## Mise en service du téléphone

1. Android 8 ou plus. Installer l'APK et accorder les permissions, dont **Appels téléphoniques**.
2. Dans l'application : Paramètres > USSD > **Code PIN Mobile Money**. Il reste sur le téléphone et n'est
   pas exporté par `/settings`.
3. Désactiver l'optimisation de batterie pour l'application.
