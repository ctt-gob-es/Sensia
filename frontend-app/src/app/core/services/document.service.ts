/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */


import { Injectable, signal } from '@angular/core';
import { DocumentItemUI } from '../interfaces/types';

@Injectable({
  providedIn: 'root'
})
export class DocumentService {
  documents = signal<DocumentItemUI[]>([]);

  setDocuments(docs: DocumentItemUI[]) {
    this.documents.set(docs);
  }

  addDocument(doc: DocumentItemUI) {
    this.documents.update(current => [...current, doc]);
  }

  removeDocument(docId: string) {
    this.documents.update(current => current.filter(d => d.id !== docId));
  }

  clearAll() {
    this.documents.set([]);
  }
}