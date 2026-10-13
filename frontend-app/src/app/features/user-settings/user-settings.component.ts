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

import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { UserService } from '../../core/services/user.service';
import { RedactionTemplate } from '../../core/interfaces/types';
import { TemplateService } from '../../core/services/template.service';
import {
  PATRON_ZONA,
  PATRON_ZONAS,
  palabrasATexto,
  zonaAArray,
  zonaATexto,
  zonasAArrays,
  zonasATexto
} from '../../core/utils/opciones-procesamiento';

type Pestana = 'general' | 'deteccion' | 'imagenes' | 'listas' | 'avanzado';

/** Campos con validación y la pestaña en la que se muestran */
const CAMPOS_VALIDADOS: { ruta: string; pestana: Pestana }[] = [
  { ruta: 'sensiaOptions.documentOptions.fixedRedactionZones', pestana: 'listas' },
  { ruta: 'sensiaOptions.documentOptions.logoExclusionZone', pestana: 'listas' },
];

@Component({
  selector: 'app-user-settings',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './user-settings.component.html',
  styleUrls: ['./user-settings.component.css']
})
export class UserSettingsComponent implements OnInit {
  settingsForm!: FormGroup;
  isSaving = signal(false);
  userTemplates: RedactionTemplate[] = [];
  selectedTemplate: RedactionTemplate | null = null;

  readonly pestanas: { id: Pestana; titulo: string }[] = [
    { id: 'general', titulo: 'General' },
    { id: 'deteccion', titulo: 'Detección' },
    { id: 'imagenes', titulo: 'Imágenes y rostros' },
    { id: 'listas', titulo: 'Listas y zonas' },
    { id: 'avanzado', titulo: 'Avanzado' },
  ];
  pestanaActiva: Pestana = 'general';

  private fb = inject(FormBuilder);
  private router = inject(Router);
  private userService = inject(UserService);
  private templateService = inject(TemplateService);

  ngOnInit() {
    this.initForm();
    this.loadTemplates();
    this.loadSettings();
  }

  initForm() {
    this.settingsForm = this.fb.group({
      sensiaOptions: this.fb.group({
        redactionPreview: [true],
        documentOptions: this.fb.group({
          redactQRCodes: [true],
          redactNames: [true],
          redactNERNames: [false],
          redactNEROrg: [false],
          redactNERLoc: [false],
          redactNERMisc: [false],
          redactNERScore: 0.65,
          removeImages: [false],
          preserveLogo: [true],
          allowedWords: [''],
          blockedWords: [''],
          fixedRedactionZones: ['', Validators.pattern(PATRON_ZONAS)],
          logoExclusionZone: ['', Validators.pattern(PATRON_ZONA)],
          redactionThickness: [2],
          usePseudonyms: [false]
        }),
        faceOptions: this.fb.group({
          maskFaces: [true],
          minFaceAspectRatio: [0.0],
          maxFaceAspectRatio: [0.0],
          minFaceArea: [0.0],
          maxFaceArea: [0.0],
          pixelationIntensity: [70]
        }),
        yunetOptions: this.fb.group({
          scoreThreshold: [0.30],
          nmsThreshold: [0.45],
          topK: [5000]
        })
      }),
      appOptions: this.fb.group({
        askTemplateBeforeRedact: [false],
        defaultTemplate: ['']
      })
    });
  }

  // ---------------------------------------------------------------------------
  // Ayudas para la plantilla
  // ---------------------------------------------------------------------------

  /** La pestaña contiene algún campo con formato incorrecto */
  tieneErrores(pestana: Pestana): boolean {
    return CAMPOS_VALIDADOS.some(c => c.pestana === pestana && !!this.settingsForm.get(c.ruta)?.invalid);
  }

  /** El campo tiene un formato incorrecto y el usuario ya lo ha tocado */
  invalido(ruta: string): boolean {
    const control = this.settingsForm.get(ruta);
    return !!control && control.invalid && (control.dirty || control.touched);
  }

  /** Valor actual de un control, para mostrarlo junto a un deslizador */
  valor(ruta: string): unknown {
    return this.settingsForm.get(ruta)?.value;
  }


  loadTemplates() {
    this.templateService.getTemplates().subscribe({
      next: (templates) => {
        this.userTemplates = templates
        this.selectedTemplate = null;

      },
      error: (err) => console.error('Error al cargar plantillas', err)
    });
  }


  onTemplateSelect(event: Event) {
    const selectElement = event.target as HTMLSelectElement;
    const templateId = selectElement.value ? Number(selectElement.value) : null;
    this.selectTemplateById(templateId);
  }

  selectTemplateById(id: number | undefined | null) {
    if (!id) {
      this.selectedTemplate = null;
      return;
    }

    const found = this.userTemplates.find(t => t.id === id);
    if (found) {
      this.selectedTemplate = found;
    }
  }


  loadSettings() {
    this.userService.getSettings().subscribe({
      next: (user) => {
        if (user.settings && user.settings.sensiaOptions) {
          this.settingsForm.patchValue({
            sensiaOptions: this.aFormulario(user.settings.sensiaOptions),
            appOptions: structuredClone(user.settings.appOptions ?? {})
          });
        }
      },
      error: (err) => console.error('Error al cargar configuraciones', err)
    });
  }

  

  onSubmit() {
    if (this.settingsForm.invalid) {
      this.settingsForm.markAllAsTouched();
      const conErrores = this.pestanas.find(p => this.tieneErrores(p.id));
      if (conErrores) this.pestanaActiva = conErrores.id;
      return;
    }

    this.isSaving.set(true);
    const formValue = structuredClone(this.settingsForm.value);
    const documento = formValue.sensiaOptions.documentOptions;

    // Se mantiene la forma en que guarda backend-app: listas como texto y zonas como arrays
    documento.allowedWords = palabrasATexto(documento.allowedWords);
    documento.blockedWords = palabrasATexto(documento.blockedWords);
    documento.fixedRedactionZones = zonasAArrays(documento.fixedRedactionZones);
    documento.logoExclusionZone = zonaAArray(documento.logoExclusionZone);

    this.userService.updateSettings(formValue).subscribe({
      next: (res) => {
        this.isSaving.set(false);
        alert('✅ Preferencias guardadas correctamente');
        this.settingsForm.markAsPristine();
        this.settingsForm.markAsUntouched();
      },
      error: (err) => {
        this.isSaving.set(false);
        alert('❌ Error al guardar las preferencias');
        console.error(err);
      }
    });
  }

  /**
   * sensiaOptions tal como las guarda backend-app, en la forma del formulario: backend-app guarda
   * las listas como texto y las zonas como arrays; el formulario usa texto.
   * Lo que no es válido (p. ej. el [0] que dejaba antes la zona vacía) se muestra vacío.
   */
  private aFormulario(opciones: any): any {
    const sensiaOptions = structuredClone(opciones);
    const documento = sensiaOptions.documentOptions;
    if (documento) {
      documento.allowedWords = palabrasATexto(documento.allowedWords);
      documento.blockedWords = palabrasATexto(documento.blockedWords);
      documento.fixedRedactionZones = zonasATexto(documento.fixedRedactionZones);
      documento.logoExclusionZone = zonaATexto(documento.logoExclusionZone);
    }
    return sensiaOptions;
  }


  /** Pone en el formulario las preferencias con que se crea un usuario; se guardan con «Guardar cambios» */
  restaurarPorDefecto() {
    const mensaje = 'Se restaurarán los valores por defecto de todas las pestañas. ' +
      'No se guardan hasta pulsar «Guardar cambios».\n\n¿Desea continuar?';
    if (!confirm(mensaje)) return;
    this.userService.getDefaultSettings().subscribe({
      next: (porDefecto) => {
        // reset y no patchValue: lo que no traigan los valores por defecto no conserva el valor del usuario
        this.settingsForm.get('sensiaOptions')!.reset(this.aFormulario(porDefecto?.sensiaOptions ?? {}));
        this.settingsForm.markAsDirty();
      },
      error: (err) => {
        alert('❌ No se han podido cargar los valores por defecto');
        console.error(err);
      }
    });
  }


  goBack() {
    if (this.settingsForm.dirty && !confirm('Hay cambios sin guardar. ¿Salir sin guardarlos?')) {
      return;
    }
    this.router.navigate(['/upload']);
  }

}