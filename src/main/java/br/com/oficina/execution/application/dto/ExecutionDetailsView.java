package br.com.oficina.execution.application.dto;

import br.com.oficina.execution.domain.model.TechnicalNote;
import java.time.OffsetDateTime;
import java.util.List;

/** Tarefas da OS nas duas fases e as anotações técnicas, da mais antiga para a mais recente. */
public record ExecutionDetailsView(Long workOrderId, List<TaskView> tasks, List<NoteView> notes) {

    public record NoteView(String phase, String author, String kind, String text, OffsetDateTime at) {

        public static NoteView from(TechnicalNote note) {
            return new NoteView(note.phase().name(), note.author(), note.kind().name(), note.text(), note.at());
        }
    }
}
