# Module USSD (fork)

Ajout à SMS Gateway for Android : exécuter des codes USSD à plusieurs écrans (menus Mobile Money)
à la demande de l'API locale. Tout le code est dans `modules/ussd/` et `localserver/routes/UssdRoutes.kt`.

## Principe

1. Le serveur envoie `POST /ussd` avec le code et les réponses à saisir, écran par écran.
2. Le téléphone compose le code. Le service d'accessibilité lit chaque boîte USSD et saisit la réponse suivante.
3. En fin de session, le webhook `ussd:completed` ou `ussd:failed` part avec les textes affichés.

**Le téléphone ne confirme aucun transfert.** La preuve reste le SMS de l'opérateur
(« Envoi de … vers le … »), analysé par le serveur. Un `ussd:failed`, en particulier après un
délai dépassé, ne prouve pas que l'argent n'est pas parti.

## API

`POST /ussd` (authentification Basic de l'API locale) :

```json
{
  "id": "transfert-2026-000123",
  "code": "*144#",
  "answers": ["1", "1", "620000000", "5000", "{PIN}"],
  "simNumber": 1,
  "timeoutSeconds": 90
}
```

| Champ | Règle |
|---|---|
| `id` | Clé d'idempotence, 1 à 64 caractères `A-Za-z0-9_-`. Absent : généré. |
| `code` | Chiffres, `*` et `#`, terminé par `#`. |
| `answers` | Une réponse par écran de saisie, 15 au plus. `{PIN}` est remplacé sur le téléphone. |
| `simNumber` | 1, 2… Absent : SIM par défaut. |
| `timeoutSeconds` | 10 à 300, défaut 90. |

Réponses :
- `202` : session en file, avec son état.
- `400` : requête invalide (message explicite).
- `409` : `id` déjà composé, mais son état a été perdu (redémarrage). Ne jamais renvoyer avec un autre id avant d'avoir vérifié le SMS.

`GET /ussd/{id}` : état de la session (`Pending`, `Running`, `Completed`, `Failed`), écrans affichés,
message final, erreur. `404` si la session est inconnue depuis le dernier démarrage de l'application.

Webhooks : enregistrer `ussd:completed` et `ussd:failed` comme les autres événements (`POST /webhooks`).
Le contenu est le même objet que `GET /ussd/{id}`.

## Règles de la session

- Une session à la fois, les suivantes attendent en file.
- Un écran avec champ de saisie reçoit la réponse suivante.
- Un écran de saisie alors qu'il ne reste aucune réponse : session annulée, `ussd:failed`.
- Un écran sans saisie avant la dernière réponse (erreur de l'opérateur) : `ussd:failed`, le texte est dans `finalMessage`.
- Un écran sans saisie après la dernière réponse : `ussd:completed`.
- Un id composé est enregistré AVANT de composer : il n'est jamais recomposé, même après redémarrage.

## Mise en service du téléphone

1. Installer l'APK et accorder les permissions demandées, dont **Appels téléphoniques**.
2. Android 13 et plus : Paramètres > Applications > SMS Gateway > menu ⋮ > **Autoriser les paramètres restreints**.
3. Paramètres Android > Accessibilité > **SMS Gateway USSD** : activer.
4. Dans l'application : Paramètres > USSD > **Code PIN Mobile Money**. Il reste sur le téléphone et n'est pas exporté par `/settings`.
5. Désactiver l'optimisation de batterie (Paramètres de l'application > Système).

Les boîtes USSD sont attendues dans le paquet `com.android.phone`. Si un téléphone en utilise un autre,
il apparaît dans les logs `UssdAccessibility` ; l'ajouter dans `res/xml/ussd_accessibility_service.xml`.
