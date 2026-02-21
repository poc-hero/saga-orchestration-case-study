# kubectl – Aide-mémoire

Commandes kubectl pour le projet Saga sur k3s. Namespace par défaut : `saga-k8s-dev`.

---

## Cluster

```bash
kubectl get nodes
kubectl get nodes -o wide
kubectl get namespaces
```

---

## Pods

```bash
kubectl get pods -n saga-k8s-dev
kubectl get pods -n saga-k8s-dev -o wide
kubectl get pods -A

kubectl describe pod <NOM_POD> -n saga-k8s-dev
```

---

## Logs

```bash
kubectl logs <NOM_POD> -n saga-k8s-dev
kubectl logs <NOM_POD> -n saga-k8s-dev --tail=100
kubectl logs <NOM_POD> -n saga-k8s-dev --previous

# Follow (temps réel)
kubectl logs -l app=site-service -n saga-k8s-dev -f
kubectl logs -l app=uaa-service -n saga-k8s-dev -f
```

---

## Deployments

```bash
kubectl get deployments -n saga-k8s-dev
kubectl describe deployment site-service -n saga-k8s-dev

# Rollout
kubectl rollout status deployment site-service -n saga-k8s-dev
kubectl rollout history deployment site-service -n saga-k8s-dev
kubectl rollout restart deployment site-service -n saga-k8s-dev
```

---

## Services

```bash
kubectl get svc -n saga-k8s-dev
kubectl describe svc site-service -n saga-k8s-dev

# Patcher en NodePort (accès externe)
kubectl patch svc site-service -n saga-k8s-dev \
  -p '{"spec":{"type":"NodePort","ports":[{"port":8080,"targetPort":8080,"nodePort":30080}]}}'
```

---

## ConfigMaps et Secrets

```bash
kubectl get configmaps -n saga-k8s-dev
kubectl get secrets -n saga-k8s-dev
kubectl describe configmap site-service-config -n saga-k8s-dev
```

---

## Ingress

```bash
kubectl get ingress -n saga-k8s-dev
kubectl describe ingress site-service -n saga-k8s-dev
```

---

## Port-forward

```bash
# Arrière-plan (ajouter &)
kubectl port-forward svc/site-service 9090:8080 -n saga-k8s-dev --address=0.0.0.0 &

# Tester
curl http://localhost:9090/actuator/health
```

---

## Déploiement / suppression

```bash
# Appliquer (Kustomize)
kubectl apply -k ~/k8s/saga-k8s/overlays/dev/

# Supprimer
kubectl delete -k ~/k8s/saga-k8s/overlays/dev/

# Supprimer un pod (recréé automatiquement)
kubectl delete pod <NOM_POD> -n saga-k8s-dev
```

---

## Debug

```bash
# Shell dans un pod
kubectl exec -it <NOM_POD> -n saga-k8s-dev -- sh

# Connectivité entre services
kubectl exec -it <NOM_POD> -n saga-k8s-dev -- wget -qO- http://uaa-service:8080/actuator/health

# Événements récents
kubectl get events -n saga-k8s-dev --sort-by='.lastTimestamp'

# Toutes les ressources
kubectl get all -n saga-k8s-dev
```
