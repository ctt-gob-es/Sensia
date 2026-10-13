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

import { Component, EventEmitter, HostListener, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RedactionTemplate } from '../../core/interfaces/types';
import { TemplateService } from '../../core/services/template.service';
import { AuthService } from '../../core/services/auth.service';
import { DocumentProcessingService } from '../../core/services/document-processing.service';
import {
  AjustesDocumento,
  PATRON_ZONA,
  PATRON_ZONAS,
  aPalabras,
  aZona,
  aZonas,
  ajustesDesdeOpciones,
  contieneZona,
  opcionesDesdeAjustes,
  palabrasATexto,
  zonaATexto,
  zonasATexto
} from '../../core/utils/opciones-procesamiento';
import { mismaFrase } from '../../core/utils/seleccion-visor';

/** Lo que devuelve el diálogo: los ajustes con los que se vuelve a analizar todo el documento */
export interface ResultadoRepetir {
  ajustes: AjustesDocumento;
}

/**
 * Diálogo «Repetir análisis»: ajustes propios de un documento (o de una plantilla).
 * Las reglas generales de detección se configuran en Preferencias.
 * Siempre termina en un análisis completo; las decisiones del panel se conservan.
 */
@Component({
  selector: 'app-doc-reprocess',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './doc-reprocess.component.html',
  styleUrls: ['./doc-reprocess.component.css']
})
export class DocReprocessComponent implements OnInit {
  /** Ajustes actuales del documento */
  @Input() ajustes!: AjustesDocumento;
  /** Lo que se muestra al abrir: los ajustes más los cambios pendientes del visor, si los hay */
  @Input() inicial?: AjustesDocumento;
  /** El documento no tiene resultado: se analiza desde el principio */
  @Input() soloAnalisis = false;

  @Output() cancel = new EventEmitter<void>();
  @Output() confirm = new EventEmitter<ResultadoRepetir>();

  reprocessForm!: FormGroup;
  isProcessing = false;
  isSavingTemplate = false;
  isDeletingTemplate = false;
  userTemplates: RedactionTemplate[] = [];
  selectedTemplate: RedactionTemplate | null = null;

  private globales?: AjustesDocumento; // ajustes de las opciones globales vigentes
  private fb = inject(FormBuilder);
  private templateService = inject(TemplateService);
  private authService = inject(AuthService);
  private procesamiento = inject(DocumentProcessingService);

  ngOnInit() {
    this.reprocessForm = this.fb.group({
      bloqueadas: [''],
      permitidas: [''],
      zonasFijas: ['', Validators.pattern(PATRON_ZONAS)],
      zonaLogo: ['', Validators.pattern(PATRON_ZONA)],
      eliminarImagenes: [false]
    });
    this.mostrarAjustes(this.inicial ?? this.ajustes);
    this.loadTemplates();
    this.procesamiento.ajustesGlobales()
      .then(ajustes => this.globales = ajustes)
      .catch(err => console.error('Error al cargar las opciones globales', err));
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.onCancel();
  }

  // ---------------------------------------------------------------------------
  // Estado del diálogo
  // ---------------------------------------------------------------------------

  /** El campo tiene un formato incorrecto y el usuario ya lo ha tocado */
  invalido(campo: string): boolean {
    const control = this.reprocessForm.get(campo);
    return !!control && control.invalid && (control.dirty || control.touched);
  }

  /** La plantilla elegida es del usuario; las públicas de otros solo se pueden usar */
  get plantillaPropia(): boolean {
    return !!this.selectedTemplate && this.selectedTemplate.propia !== false;
  }


  onSubmit() {
    if (this.reprocessForm.invalid) {
      this.reprocessForm.markAllAsTouched();
      return;
    }
    this.isProcessing = true;
    this.confirm.emit({ ajustes: this.ajustesDelFormulario() });
  }

  onCancel() {
    this.cancel.emit();
  }

  // ---------------------------------------------------------------------------
  // Punto de partida: opciones globales o una plantilla
  // ---------------------------------------------------------------------------

  loadTemplates() {
    this.templateService.getTemplates().subscribe({
      next: (templates) => {
        this.userTemplates = templates;
        // Se marca la plantilla de la que partió el documento, sin tocar sus valores actuales
        this.selectedTemplate = templates.find(t => t.id === this.ajustes.plantillaId) ?? null;
      },
      error: (err) => console.error('Error al cargar plantillas', err)
    });
  }

  onTemplateSelect(event: Event) {
    const valor = (event.target as HTMLSelectElement).value;
    this.selectTemplateById(valor ? Number(valor) : null);
  }

  async selectTemplateById(id: number | undefined | null) {
    const found = id ? this.userTemplates.find(t => t.id === id) : undefined;
    this.selectedTemplate = found ?? null;

    if (found) {
      // Lo que la plantilla no trae se toma de las opciones globales
      this.mostrarAjustes(this.conPendientesDelVisor(
        ajustesDesdeOpciones(found.options, this.globales ?? this.ajustes)));
      return;
    }

    try {
      this.globales ??= await this.procesamiento.ajustesGlobales();
      this.mostrarAjustes(this.conPendientesDelVisor(this.globales));
    } catch (err) {
      console.error('Error al cargar las opciones globales', err);
      alert('❌ No se han podido cargar las opciones globales. Inténtalo de nuevo en unos segundos.');
    }
  }

  saveOrUpdateTemplate() {
    if (this.reprocessForm.invalid) {
      this.reprocessForm.markAllAsTouched();
      return;
    }
    if (this.plantillaPropia) {
      this.updateCurrentTemplate();
    } else {
      this.saveCurrentTemplate();
    }
  }

  saveCurrentTemplate() {
    const templateName = window.prompt('Nombre de la plantilla (ej. "Liquidaciones", "Nóminas"):');

    if (templateName && templateName.trim() !== '') {
      // Solo un administrador puede publicarla; el servidor lo vuelve a comprobar
      const publica = this.authService.isAdmin() && confirm(
        '¿Hacer pública la plantilla?\n\n' +
        'Aceptar: la podrán usar todos los usuarios, que verán también sus listas de palabras.\n' +
        'Cancelar: solo la usarás tú.');      
      this.isSavingTemplate = true;

      this.templateService.saveTemplate(templateName.trim(), opcionesDesdeAjustes(this.ajustesDelFormulario()), publica).subscribe({
        next: (res) => {
          this.isSavingTemplate = false;
          this.userTemplates.unshift(res.template);
          this.selectedTemplate = res.template;
          alert(`✅ Plantilla "${res.template.name}" guardada correctamente.`);
        },
        error: (err) => {
          this.isSavingTemplate = false;
          console.error('Error al guardar plantilla', err);
          alert('❌ Ha ocurrido un error al guardar la plantilla.');
        }
      });
    }
  }

  updateCurrentTemplate() {
    if (this.selectedTemplate?.id) {
      this.isSavingTemplate = true;

      this.templateService.updateTemplate(this.selectedTemplate.id, opcionesDesdeAjustes(this.ajustesDelFormulario())).subscribe({
        next: (res) => {
          this.isSavingTemplate = false;

          const index = this.userTemplates.findIndex(t => t.id === res.template.id);
          if (index !== -1) {
            // Si la respuesta no trae isPublic ni propia: se conservan los de la lista
            this.userTemplates[index] = { ...this.userTemplates[index], ...res.template };
            this.selectedTemplate = this.userTemplates[index];
          }

          alert(`✅ Plantilla "${res.template.name}" actualizada correctamente.`);
        },
        error: (err) => {
          this.isSavingTemplate = false;
          console.error('Error al actualizar plantilla', err);
          alert('❌ Ha ocurrido un error al actualizar la plantilla.');
        }
      });
    }
  }

  deleteCurrentTemplate() {
    if (!this.selectedTemplate) {
      return;
    }
    const confirmacion = confirm(`¿Estás seguro de que deseas eliminar la plantilla "${this.selectedTemplate.name}"?`);
    if (!confirmacion) {
      return;
    }
    this.isDeletingTemplate = true;
    const idABorrar = this.selectedTemplate.id;
    const nombreABorrar = this.selectedTemplate.name;

    this.templateService.deleteTemplate(idABorrar).subscribe({
      next: () => {
        this.isDeletingTemplate = false;
        this.userTemplates = this.userTemplates.filter(t => t.id !== idABorrar);
        this.selectTemplateById(null);
        alert(`✅ Plantilla "${nombreABorrar}" eliminada correctamente.`);
      },
      error: (err) => {
        this.isDeletingTemplate = false;
        console.error('Error al eliminar plantilla', err);
        alert('❌ Ha ocurrido un error al eliminar la plantilla.');
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Formulario <-> ajustes
  // ---------------------------------------------------------------------------

  /**
   * Añade a unos ajustes lo marcado en el visor y aún sin aplicar (lo que `inicial` trae de más
   * sobre `ajustes`), para que no se pierda al cambiar el punto de partida.
   */
  private conPendientesDelVisor(base: AjustesDocumento): AjustesDocumento {
    if (!this.inicial) return base;
    const resultado = structuredClone(base);
    for (const frase of this.inicial.bloqueadas) {
      const pendiente = !this.ajustes.bloqueadas.includes(frase);
      if (pendiente && !resultado.bloqueadas.some(f => mismaFrase(f, frase))) {
        resultado.bloqueadas.push(frase);
      }
    }
    for (const zona of this.inicial.zonasFijas) {
      const pendiente = !contieneZona(this.ajustes.zonasFijas, zona);
      if (pendiente && !contieneZona(resultado.zonasFijas, zona)) {
        resultado.zonasFijas.push({ ...zona });
      }
    }
    return resultado;
  }

  private mostrarAjustes(ajustes: AjustesDocumento | undefined) {
    this.reprocessForm.reset({
      bloqueadas: palabrasATexto(ajustes?.bloqueadas),
      permitidas: palabrasATexto(ajustes?.permitidas),
      zonasFijas: zonasATexto(ajustes?.zonasFijas),
      zonaLogo: zonaATexto(ajustes?.zonaLogo),
      eliminarImagenes: !!ajustes?.eliminarImagenes
    });
  }

  private ajustesDelFormulario(): AjustesDocumento {
    const valor = this.reprocessForm.value;
    return {
      bloqueadas: aPalabras(valor.bloqueadas),
      permitidas: aPalabras(valor.permitidas),
      zonasFijas: aZonas(valor.zonasFijas),
      // Zonas de una sola página: no se editan aquí ni forman parte de una plantilla
      zonasPagina: structuredClone((this.inicial ?? this.ajustes).zonasPagina ?? []),
      zonaLogo: aZona(valor.zonaLogo),
      eliminarImagenes: !!valor.eliminarImagenes,
      plantillaId: this.selectedTemplate?.id ?? null
    };
  }
}