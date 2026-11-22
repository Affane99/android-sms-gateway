# Module USSD (fork)

Ajout à SMS Gateway for Android : exécuter des codes USSD à plusieurs écrans (menus Mobile Money)
à la demande de l'API locale. Tout le code est dans `modules/ussd/` et `localserver/routes/UssdRoutes.kt`.

## Principe

1. Le serveur envoie `POST /ussd` avec le code et les étapes : pour chaque écran, le texte attendu et la réponse.
2. Le téléphone compose le code. Le service d'accessibilité lit chaque boîte USSD, vérifie qu'elle contient
   le texte attendu, puis saisit la réponse. Un écran inattendu annule la session sans rien saisir.
3. En fin de session, le webhook `ussd:completed` ou `ussd:failed` part avec les textes affichés.

**Le téléphone ne confirme aucun transfert.** La preuve reste le SMS de l'opérateur
(« Envoi de … vers le … »), analysé par le serveur. Un `ussd:failed`, en particulier après un
délai dépassé, ne prouve pas que l'argent n'est pas parti.

## API

`POST /ussd` (authentification Basic de l'API locale) :

Exemple : transfert national Orange Money Guinée de 1 000 GNF vers le 627023108. Le code de départ
mène directement au récapitulatif ; une seule session, PIN compris.

```json
{
  "id": "0f8fad5bd9cb469fa16570867728950e",
  "code": "*144*1*1*627023108*1000*1#",
  "steps": [
    { "expect": "Vous transferez 1000 GNF vers le 627023108", "answer": "1" },
    { "expect": "code secret", "answer": "{PIN}" }
  ],
  "simNumber": 1,
  "timeoutSeconds": 90
}
```

L'étape 1 est la protection essentielle : le récapitulatif n'est confirmé que s'il contient
le montant ET le numéro exacts. Sinon la session est annulée sans rien saisir.

| Champ | Règle |
|---|---|
| `id` | Clé d'idempotence, 1 à 64 caractères `A-Za-z0-9_-`. Absent : généré. |
| `code` | Chiffres, `*` et `#`, terminé par `#`. |
| `steps` | Un élément par écran de saisie, 15 au plus. |
| `steps[].expect` | Texte que l'écran doit contenir (casse, accents et espaces ignorés). Facultatif, mais obligatoire en pratique pour un transfert. |
| `steps[].answer` | Réponse saisie. `{PIN}` est remplacé sur le téléphone. |
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
- Un écran de saisie qui contient le texte attendu reçoit la réponse de l'étape.
- Un écran de saisie qui ne le contient pas : session annulée sans rien saisir, `ussd:failed`.
- Un écran de saisie alors qu'il ne reste aucune étape : session annulée, `ussd:failed`.
- Un écran sans saisie avant la dernière étape (erreur de l'opérateur) : `ussd:failed`, le texte est dans `finalMessage`.
- Un écran sans saisie après la dernière étape : `ussd:completed`.
- Un id composé est enregistré AVANT de composer : il n'est jamais recomposé, même après redémarrage.

## Mise en service du téléphone

1. Installer l'APK et accorder les permissions demandées, dont **Appels téléphoniques**.
2. Android 13 et plus : Paramètres > Applications > SMS Gateway > menu ⋮ > **Autoriser les paramètres restreints**.
3. Paramètres Android > Accessibilité > **SMS Gateway USSD** : activer.
4. Dans l'application : Paramètres > USSD > **Code PIN Mobile Money**. Il reste sur le téléphone et n'est pas exporté par `/settings`.
5. Désactiver l'optimisation de batterie (Paramètres de l'application > Système).

Les boîtes USSD sont attendues dans le paquet `com.android.phone`. Si un téléphone en utilise un autre,
le changer dans `res/xml/ussd_accessibility_service.xml` ET dans `USSD_PACKAGE`
(`UssdAccessibilityService.kt`). Seules les fenêtres de ce paquet sont lues.
